package ist.depchain.network.abstractions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.protobuf.ByteString;

import ist.depchain.common.Ack;
import ist.depchain.common.Envelope;
import ist.depchain.common.utils.Config;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

public class PerfectLink implements Link {

    private final Config config;
    private final Link stubbornLink;
    private final Link fairLossLink;

    private MessageHandler handler; // upper layer's message handler (e.g., application layer, authenticated perfect link layer, etc.)

    // per-destination outgoing sequence counters; prevents gaps when mixing unicasts and broadcasts (a unicast to s2 must not create a gap for s3).
    private final Map<String, AtomicLong> outgoingMessagesSeqNumbers = new ConcurrentHashMap<>();
    
    // per-destination pending retransmission handles: dest -> seq -> handle
    private final Map<String, Map<Long, SendHandle>> messagesAwaitingForAck = new ConcurrentHashMap<>();

    // ordering
    private Map<String /*sender*/, Long /*next_expected_seq_num*/> nextExpected = new ConcurrentHashMap<>(); // for tracking the next expected sequence number from each sender to detect duplicates and ensure in-order delivery
    private Map<String /*sender*/, Map<Long /*seqNum*/, byte[] /*payload*/>> pendingDeliveries = new ConcurrentHashMap<>(); // for buffering out-of-order messages until they can be delivered in order
    
    public PerfectLink(Config config, Link stubbornLink, Link fairLossLink) {
        this.config = config;
        this.stubbornLink = stubbornLink;
        this.fairLossLink = fairLossLink;

        stubbornLink.registerReceiver(this::handleIncomingMessage);
    }

    // ============================================================
    // SEND (UNICAST)
    // ============================================================

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        long seq = outgoingMessagesSeqNumbers.computeIfAbsent(destinationId, k -> new AtomicLong(0)).incrementAndGet();
        return sendWithSeqNumber(destinationId, payload, seq);
    }

    /**
     * Sends with a caller-supplied sequence number.
     * Used by AuthenticatedPerfectLink so it can sign the payload with the seq
     * before handing it here (freshness: HMAC covers seq || payload).
     */
    SendHandle sendWithSeqNumber(String destinationId, byte[] payload, long seq) {

        Envelope envelope = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setSequenceNumber(seq)
                .setPayload(ByteString.copyFrom(payload))
                .build();

        SendHandle handle = stubbornLink.send(destinationId, envelope.toByteArray());
        messagesAwaitingForAck.computeIfAbsent(destinationId, k -> new ConcurrentHashMap<>()).put(seq, handle);

        return handle;
    }

    // ============================================================
    // BROADCAST
    // ============================================================

    @Override
    public Map<String, SendHandle> broadcast(Set<String> destinationIds, byte[] payload) {
        Map<String, SendHandle> handles = new ConcurrentHashMap<>();
        SendHandle handle = null;
        for (String dest : destinationIds) {
            handle = send(dest, payload);
            handles.put(dest, handle);
        }
        return handles;
    }

    // ============================================================
    // RECEIVE
    // ============================================================

    void handleIncomingMessage(String senderId, byte[] data) {

        try {
            Envelope envelope = Envelope.parseFrom(data);

            // ACK: stop retransmission of the acknowledged message
            if (envelope.hasAck()) {
                handleAck(senderId, envelope.getAck());
                return;
            }

            long seq = envelope.getSequenceNumber();
            long expected = nextExpected.getOrDefault(senderId, 1L);

            sendAck(senderId, seq);

            if (seq == expected) {
                // this is the expected message, can be delivered immediately
                // System.out.println("[PERFECT_LINK | INFO] - Received expected message with seq " + seq + " from " + senderId);
                deliverToUpperLayer(senderId, envelope.getPayload().toByteArray());
                nextExpected.put(senderId, seq + 1);

                // after delivering this message, check if we have buffered messages that can now be delivered
                deliverBufferedToUpperLayer(senderId);

            } else if (seq > expected) {
                // out-of-order message, buffer it until we can deliver it in order
                // System.out.println("[PERFECT_LINK | INFO] - Received out-of-order message with seq " + seq + " from " + senderId + ", expected seq is " + expected + ". Buffering.");
                pendingDeliveries.computeIfAbsent(senderId, k -> new ConcurrentHashMap<>())
                        .put(seq, envelope.getPayload().toByteArray());

            }
            // seq < expected -> duplicate, ignore

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ============================================================
    // DELIVERY LOGIC
    // ============================================================

    private void deliverToUpperLayer(String senderId, byte[] payload) {
        if (handler != null) {
            handler.onReceive(senderId, payload);
        }
    }

    private void deliverBufferedToUpperLayer(String senderId) {

        Map<Long, byte[]> buffer = pendingDeliveries.get(senderId);
        if (buffer == null) return;

        long next = nextExpected.get(senderId);

        while (buffer.containsKey(next)) {
            byte[] payload = buffer.remove(next);
            deliverToUpperLayer(senderId, payload);
            nextExpected.put(senderId, ++next);
        }
    }

    private void handleAck(String senderId, Ack ack) {

        long seq = ack.getOriginalSequenceNumber();

        // senderId is the ACK sender = the original receiver of our message
        // messagesAwaitingForAck[senderId][seq] is the retransmission handle for that message
        Map<Long, SendHandle> messagesAwaitingForAckForDestination = messagesAwaitingForAck.get(senderId);
        if (messagesAwaitingForAckForDestination == null) return;

        // cancel the retransmission task for this message, since it has been acknowledged
        SendHandle handle = messagesAwaitingForAckForDestination.remove(seq);
        if (handle != null) handle.cancel();
    }

    private void sendAck(String destinationId, long seq) {

        Ack ack = Ack.newBuilder()
                .setOriginalSender(destinationId)
                .setOriginalSequenceNumber(seq)
                .build();

        Envelope envelope = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setSequenceNumber(0) // ACK messages don't need own seq
                .setAck(ack)
                .build();

        fairLossLink.send(destinationId, envelope.toByteArray());
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

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

    public Map<String, AtomicLong> getOutgoingMessagesSeqNumbers() {
        return outgoingMessagesSeqNumbers;
    }
}
