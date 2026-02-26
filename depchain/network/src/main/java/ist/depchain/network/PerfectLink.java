package ist.depchain.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.google.protobuf.ByteString;

import ist.depchain.common.*;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

public class PerfectLink implements Link {

    private String selfId; 
    private final Link stubbornLink;  // StubbornLink
    private final Link fairLossLink; // for sending ACKs, we can use the underlying fair loss link directly since ACKs are idempotent and don't require retransmission
    private Map<Long /*seqNum*/, SendHandle> pendingMessages = new ConcurrentHashMap<>(); // for tracking pending messages and their retransmission tasks
    private Map<String /*sender*/, Long /*next_expected_seq_num*/> nextExpected = new ConcurrentHashMap<>(); // for tracking the next expected sequence number from each sender to detect duplicates and ensure in-order delivery
    private Map<String /*sender*/, Map<Long /*seqNum*/, byte[] /*payload*/>> pendingDeliveries = new ConcurrentHashMap<>(); // for buffering out-of-order messages until they can be delivered in order
    private AtomicLong localSequenceCounter = new AtomicLong(0); // for generating unique sequence numbers for outgoing messages

    private MessageHandler handler; // upper layer's message handler (e.g., application layer, where the programmer defines how to process the received messages)

    public PerfectLink(String selfId, Link stubbornLink, Link fairLossLink) {
        this.selfId = selfId;
        this.stubbornLink = stubbornLink;
        this.fairLossLink = fairLossLink;
        stubbornLink.registerReceiver(this::handleIncomingMessage);
    }

    @Override
    public SendHandle send(String destinationId, byte[] payload) {

        long seq = localSequenceCounter.incrementAndGet();

        Envelope envelope = Envelope.newBuilder()
            .setSenderId(selfId)
            .setSequenceNumber(seq)
            .setPayload(ByteString.copyFrom(payload))
            .build();

        byte[] bytes = envelope.toByteArray();

        SendHandle handle = stubbornLink.send(destinationId, bytes);

        pendingMessages.putIfAbsent(seq, handle);

        return handle;
    }

    private void handleIncomingMessage(String senderId, byte[] data) {
        try {
            Envelope envelope = Envelope.parseFrom(data);

            // ACK: stop retransmission of the acknowledged message
            if (envelope.hasAck()) {
                Ack ack = envelope.getAck();
                handleAck(ack);
                return;
            }

            // NORMAL MESSAGE: process it if it's new, and send ACK back
            long seq = envelope.getSequenceNumber();

            long nextExpectedSeq = nextExpected.getOrDefault(senderId, 1L);

            sendAck(senderId, seq);
            
            if (seq == nextExpectedSeq) {
                // this is the expected message, can be delivered immediately
                System.out.println("PerfectLink: Received expected message from " + senderId + " with seq " + seq);
                nextExpected.put(senderId, seq + 1); // update next expected for this sender
                
                if (handler != null) { 
                    handler.onReceive(senderId, envelope.getPayload().toByteArray()); // deliver to upper layer
                }

                // after delivering this message, check if we have buffered messages that can now be delivered
                Map<Long, byte[]> buffered = pendingDeliveries.getOrDefault(senderId, new ConcurrentHashMap<>());
                while (buffered.containsKey(nextExpected.get(senderId))) { // we have the next expected message buffered, can deliver it now
                    long bufferedSeq = nextExpected.get(senderId);
                    byte[] bufferedPayload = buffered.remove(bufferedSeq);
                    System.out.println("PerfectLink: Now delivering previously buffered message from " + senderId + " with seq " + bufferedSeq);
                    handler.onReceive(senderId, bufferedPayload);
                    nextExpected.put(senderId, bufferedSeq + 1); // update next expected
                    // already sent ACK for this buffered message when we first received it, so no need to send ACK again
                }
                return;
            }             
            
            if (seq < nextExpectedSeq) { // duplicate or old message, just ACK again 
                System.out.println("PerfectLink: Received duplicate/old message from " + senderId + " with seq " + seq + ", next expected was " + nextExpectedSeq);
                return;
            }

            // out-of-order message, buffer it until we can deliver it in order
            if (seq > nextExpectedSeq) {
                System.out.println("PerfectLink: Received out-of-order message from " + senderId + " with seq " + seq + ", expected was " + nextExpectedSeq + ", buffering it");
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
        if (handle == null) return;
        
        handle.cancel();
        System.out.println("PerfectLink: Received ACK for seq " + seq + " from " + ack.getOriginalSender() + ", stopped retransmission");
    }

    private void sendAck(String destinationId, long seq) {

        Ack ack = Ack.newBuilder()
                .setOriginalSender(destinationId)
                .setOriginalSequenceNumber(seq)
                .build();

        Envelope ackEnvelope = Envelope.newBuilder()
                .setSenderId(selfId)
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
