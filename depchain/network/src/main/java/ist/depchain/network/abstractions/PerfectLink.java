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

    private MessageHandler handler;

    // Per-destination outgoing sequence counters — prevents gaps when mixing
    // unicasts and broadcasts (a unicast to s2 must not create a gap for s3).
    private final Map<String, AtomicLong> outSeq = new ConcurrentHashMap<>();

    // Per-destination pending retransmission handles: dest → seq → handle
    private final Map<String, Map<Long, SendHandle>> pending = new ConcurrentHashMap<>();

    // ordering
    private final Map<String, Long> nextExpected = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, byte[]>> pendingDeliveries = new ConcurrentHashMap<>();

    public PerfectLink(Config config, Link stubbornLink, Link fairLossLink) {
        this.config = config;
        this.stubbornLink = stubbornLink;
        this.fairLossLink = fairLossLink;

        stubbornLink.registerReceiver(this::ingest);
    }

    // ============================================================
    // SEND (UNICAST)
    // ============================================================

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        long seq = outSeq.computeIfAbsent(destinationId, k -> new AtomicLong(0))
                         .incrementAndGet();
        return sendWithSeq(destinationId, payload, seq);
    }

    /**
     * Sends with a caller-supplied sequence number.
     * Used by AuthenticatedPerfectLink so it can sign the payload with the seq
     * before handing it here (freshness: HMAC covers seq || payload).
     */
    SendHandle sendWithSeq(String destinationId, byte[] payload, long seq) {

        Envelope envelope = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setSequenceNumber(seq)
                .setPayload(ByteString.copyFrom(payload))
                .build();

        SendHandle handle = stubbornLink.send(destinationId, envelope.toByteArray());
        pending.computeIfAbsent(destinationId, k -> new ConcurrentHashMap<>()).put(seq, handle);

        return handle;
    }

    // ============================================================
    // BROADCAST
    // ============================================================

    @Override
    public Map<String, SendHandle> broadcast(Set<String> destinationIds, byte[] payload) {
        Map<String, SendHandle> handles = new ConcurrentHashMap<>();
        for (String dest : destinationIds) {
            handles.put(dest, send(dest, payload));
        }
        return handles;
    }

    // ============================================================
    // RECEIVE
    // ============================================================

    void ingest(String senderId, byte[] data) {

        try {
            Envelope envelope = Envelope.parseFrom(data);

            // ACK?
            if (envelope.hasAck()) {
                handleAck(senderId, envelope.getAck());
                return;
            }

            long seq = envelope.getSequenceNumber();
            long expected = nextExpected.getOrDefault(senderId, 1L);

            sendAck(senderId, seq);

            if (seq == expected) {

                deliver(senderId, envelope.getPayload().toByteArray());
                nextExpected.put(senderId, seq + 1);

                deliverBuffered(senderId);

            } else if (seq > expected) {

                pendingDeliveries
                        .computeIfAbsent(senderId, k -> new ConcurrentHashMap<>())
                        .put(seq, envelope.getPayload().toByteArray());

            }
            // seq < expected → duplicate, ignore

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ============================================================
    // DELIVERY LOGIC
    // ============================================================

    private void deliver(String senderId, byte[] payload) {
        System.out.println("Delivering payload: " + new String(payload));
        if (handler != null) {
            handler.onReceive(senderId, payload);
        }
    }

    private void deliverBuffered(String senderId) {

        Map<Long, byte[]> buffer = pendingDeliveries.get(senderId);
        if (buffer == null) return;

        long next = nextExpected.get(senderId);

        while (buffer.containsKey(next)) {
            byte[] payload = buffer.remove(next);
            deliver(senderId, payload);
            nextExpected.put(senderId, ++next);
        }
    }

    // ============================================================
    // ACK HANDLING
    // ============================================================

    private void handleAck(String senderId, Ack ack) {

        long seq = ack.getOriginalSequenceNumber();

        // senderId is the ACK sender = the original receiver of our message.
        // pending[senderId][seq] is the retransmission handle for that message.
        Map<Long, SendHandle> destPending = pending.get(senderId);
        if (destPending == null) return;

        SendHandle handle = destPending.remove(seq);
        if (handle != null) handle.cancel();
    }

    private void sendAck(String destinationId, long seq) {

        Ack ack = Ack.newBuilder()
                .setOriginalSender(destinationId)
                .setOriginalSequenceNumber(seq)
                .build();

        Envelope envelope = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setSequenceNumber(0)
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
}
