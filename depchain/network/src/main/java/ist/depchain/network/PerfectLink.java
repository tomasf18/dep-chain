package ist.depchain.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import ist.depchain.common.*;
import com.google.protobuf.ByteString;

public class PerfectLink implements Link {

    private String selfId; 
    private final Link stubbornLink;  // StubbornLink
    private final Link fairLossLink; // for sending ACKs, we can use the underlying fair loss link directly since ACKs are idempotent and don't require retransmission
    private Map<Long /*seqNum*/, SendHandle> pendingMessages = new ConcurrentHashMap<>(); // for tracking pending messages and their retransmission tasks
    private Map<String /*sender*/, Long /*highest_seq_delivered*/> deliveredSeqNums = new ConcurrentHashMap<>(); // for tracking highest sequence number this processa has delivered from each sender
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
            String envSenderId = envelope.getSenderId();
            long seq = envelope.getSequenceNumber();

            long lastDelivered = deliveredSeqNums.getOrDefault(envSenderId, -1L);

            if (seq <= lastDelivered) { // duplicate or old message, just ACK again 
                // TODO: PROBLEM -> IF SENDING 5 MESSAGES FAST, AND THE LAST ONE GETS DELIVERED FIRST, THEN ALL THE PREVIOUS ONES WILL BE CONSIDERED DUPLICATE AND NOT DELIVERED TO THE UPPER LAYER. SOLUTION: CAN'T JUST COMPARE SEQ NUMBERS, NEED TO ALSO TRACK WHICH SEQ NUMS HAVE BEEN DELIVERED (E.G., USING A BITSET OR A SET OF SEQ NUMS)
                // ALSO: CAREFUL WITH THE ORDER OF THE MESSAGES, IF THEY ARRIVE OUT OF ORDER, THEN THE "LAST DELIVERED SEQ NUM" MIGHT BE HIGHER THAN SOME NEW MESSAGE THAT JUST ARRIVED, CAUSING IT TO BE CONSIDERED DUPLICATE/OLD AND NOT DELIVERED TO THE UPPER LAYER. SOLUTION: CAN'T JUST TRACK HIGHEST SEQ NUM DELIVERED, NEED TO ALSO TRACK WHICH SEQ NUMS HAVE BEEN DELIVERED (E.G., USING A BITSET OR A SET OF SEQ NUMS)
                // LATER, TRY TO FIX THIS BY IMPLEMENTING SOMETHING SIMILAR TO TCP
                System.out.println("PerfectLink: Received duplicate/old message from " + envSenderId + " with seq " + seq + ", last delivered was " + lastDelivered);
                sendAck(envSenderId, seq);
                return;
            }

            System.out.println("PerfectLink: Received new message from " + envSenderId + " with seq " + seq + ", last delivered was " + lastDelivered);
            deliveredSeqNums.put(envSenderId, seq);

            if (handler != null) {
                handler.onReceive(envSenderId, envelope.getPayload().toByteArray());
            }

            sendAck(envSenderId, seq);

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

        fairLossLink.send(destinationId, ackEnvelope.toByteArray());
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
