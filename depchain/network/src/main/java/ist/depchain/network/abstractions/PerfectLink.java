package ist.depchain.network.abstractions;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.google.protobuf.ByteString;

import ist.depchain.common.*;
import ist.depchain.common.utils.Config;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

public class PerfectLink implements Link {

    private final Config config;
    private final Link stubbornLink;  
    private final Link fairLossLink; // for sending ACKs, we can use the underlying fair loss link directly since ACKs are idempotent and don't require retransmission
    private Map<Long /*seqNum*/, SendHandle> pendingMessages = new ConcurrentHashMap<>(); // for tracking pending messages and their retransmission tasks
    private Map<Long /*seqNum*/, Map<String /*destination*/, SendHandle>> pendingBroadcasts = new ConcurrentHashMap<>(); // for tracking pending broadcast messages and their retransmission tasks
    private Map<String /*sender*/, Long /*next_expected_seq_num*/> nextExpected = new ConcurrentHashMap<>(); // for tracking the next expected sequence number from each sender to detect duplicates and ensure in-order delivery
    private Map<String /*sender*/, Map<Long /*seqNum*/, byte[] /*payload*/>> pendingDeliveries = new ConcurrentHashMap<>(); // for buffering out-of-order messages until they can be delivered in order
    private AtomicLong localSequenceCounter = new AtomicLong(0); // for generating unique sequence numbers for outgoing messages

    private MessageHandler handler; // upper layer's message handler (e.g., application layer, authenticated perfect link layer, etc.)

    public PerfectLink(Config config, Link stubbornLink, Link fairLossLink) {
        this.config = config;
        this.stubbornLink = stubbornLink;
        this.fairLossLink = fairLossLink;
        stubbornLink.registerReceiver(this::handleIncomingMessage);
    }

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        long seq = localSequenceCounter.incrementAndGet();

        Envelope envelope = Envelope.newBuilder()
            .setSenderId(config.getSelfId())
            .setSequenceNumber(seq)
            .setPayload(ByteString.copyFrom(payload))
            .build();

        SendHandle handle = stubbornLink.send(destinationId, envelope.toByteArray());
        pendingMessages.putIfAbsent(seq, handle);
        return handle;
    }

    @Override
    public Map<String, SendHandle> broadcast(List<String> destinationIds, byte[] payload) {
        long seq = localSequenceCounter.incrementAndGet();

        Envelope envelope = Envelope.newBuilder()
            .setSenderId(config.getSelfId())
            .setSequenceNumber(seq)
            .setPayload(ByteString.copyFrom(payload))
            .build();

        Map<String, SendHandle> handlesPerDestination = new ConcurrentHashMap<>();
        for (String dest : destinationIds) {
            SendHandle handle = stubbornLink.send(dest, envelope.toByteArray());
            handlesPerDestination.put(dest, handle);
        }

        pendingBroadcasts.putIfAbsent(seq, handlesPerDestination);
        return handlesPerDestination;
    }

    private void handleIncomingMessage(String senderId, byte[] data) {
        try {
            Envelope envelope = Envelope.parseFrom(data);

            // ACK: stop retransmission of the acknowledged message
            if (envelope.hasAck()) {
                handleAck(envelope.getAck());
                return;
            }

            // NORMAL MESSAGE: process it if it's new, and send ACK back
            long seq = envelope.getSequenceNumber();
            long nextExpectedSeq = nextExpected.getOrDefault(senderId, 1L);

            sendAck(senderId, seq);
            
            if (seq == nextExpectedSeq) {
                // this is the expected message, can be delivered immediately
                //System.out.println("PerfectLink: Received expected message from " + senderId + " with seq " + seq);
                nextExpected.put(senderId, seq + 1); // update next expected for this sender
                
                if (handler != null) { 
                    handler.onReceive(senderId, envelope.getPayload().toByteArray()); // deliver to upper layer (e.g., application layer, authenticated perfect link layer, etc.)
                }

                // after delivering this message, check if we have buffered messages that can now be delivered
                Map<Long, byte[]> buffered = pendingDeliveries.getOrDefault(senderId, new ConcurrentHashMap<>());
                long next = nextExpected.get(senderId);
                while (buffered.containsKey(next)) {
                    byte[] bufferedPayload = buffered.remove(next);
                    //System.out.println("PerfectLink: Delivering buffered message from " + senderId + " seq=" + next);
                    if (handler != null) handler.onReceive(senderId, bufferedPayload);
                    nextExpected.put(senderId, next + 1);
                }
                return;
            }             
            
            if (seq < nextExpectedSeq) { // duplicate or old message, just ACK again 
                //System.out.println("PerfectLink: Received duplicate/old message from " + senderId + " with seq " + seq + ", next expected was " + nextExpectedSeq);
                return;
            }

            // out-of-order message, buffer it until we can deliver it in order
            if (seq > nextExpectedSeq) {
                //System.out.println("PerfectLink: Received out-of-order message from " + senderId + " with seq " + seq + ", expected was " + nextExpectedSeq + ", buffering it");
                pendingDeliveries.putIfAbsent(senderId, new ConcurrentHashMap<>());
                pendingDeliveries.get(senderId).put(seq, envelope.getPayload().toByteArray());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleAck(Ack ack) {
        long seq = ack.getOriginalSequenceNumber();
        SendHandle handle = pendingMessages.remove(seq);
        
        if (handle == null) {
            // this ACK is for a broadcast message
            handleBroadcastAck(seq, ack.getOriginalSender());
            return;
        }
        
        // this ACK is for a unicast message
        handle.cancel();
        //System.out.println("PerfectLink: Received ACK for seq " + seq + " from " + ack.getOriginalSender() + ", stopped retransmission");
    }

    private void handleBroadcastAck(long seq, String sender) {
        Map<String, SendHandle> broadcastHandles = pendingBroadcasts.get(seq);
        if (broadcastHandles != null) {
            SendHandle broadcastHandle = broadcastHandles.remove(sender);
            if (broadcastHandle != null) {
                broadcastHandle.cancel();
                //System.out.println("PerfectLink: Received ACK for broadcast seq " + seq + " from " + sender + ", stopped retransmission to that destination");
            }
            if (broadcastHandles.isEmpty()) {
                pendingBroadcasts.remove(seq);
            }
        }
    }

    private void sendAck(String destinationId, long seq) {
        Ack ack = Ack.newBuilder()
                .setOriginalSender(destinationId)
                .setOriginalSequenceNumber(seq)
                .build();
                
        Envelope ackEnvelope = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setSequenceNumber(0) // ACK messages don't need own seq
                .setAck(ack)
                .build();

        fairLossLink.send(destinationId, ackEnvelope.toByteArray()); // send only once
    }

    @Override
    public void registerReceiver(MessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public void start() {
        stubbornLink.start();
    }

    @Override
    public void stop() {
        stubbornLink.stop();
    }
    
}
