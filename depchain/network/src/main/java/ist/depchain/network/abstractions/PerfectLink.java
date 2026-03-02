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
import ist.depchain.network.interfaces.MessageAuthenticator;
import ist.depchain.network.interfaces.SendHandle;

public class PerfectLink implements Link {

    private final Config config;
    private final Link stubbornLink;
    private final Link fairLossLink;
    private final MessageAuthenticator authenticator;

    private MessageHandler handler;

    private final AtomicLong localSequenceCounter = new AtomicLong(0);

    // retransmission tracking
    private final Map<Long, SendHandle> pendingMessages = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, SendHandle>> pendingBroadcasts = new ConcurrentHashMap<>();

    // ordering
    private final Map<String, Long> nextExpected = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, byte[]>> pendingDeliveries = new ConcurrentHashMap<>();

    public PerfectLink(Config config,
                       Link stubbornLink,
                       Link fairLossLink,
                       MessageAuthenticator authenticator) {

        this.config = config;
        this.stubbornLink = stubbornLink;
        this.fairLossLink = fairLossLink;
        this.authenticator = authenticator;

        stubbornLink.registerReceiver(this::handleIncomingMessage);
    }

    // ============================================================
    // SEND (UNICAST)
    // ============================================================

    @Override
    public SendHandle send(String destinationId, byte[] payload) {

        long seq = localSequenceCounter.incrementAndGet();

        Envelope.Builder builder = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setSequenceNumber(seq)
                .setPayload(ByteString.copyFrom(payload));

        Envelope envelope;

        if (authenticator != null && authenticator.shouldAuthenticate(destinationId)) {
            envelope = authenticator.encrypt(destinationId, builder);
        } else {
            envelope = builder.build();
        }

        SendHandle handle = stubbornLink.send(destinationId, envelope.toByteArray());
        pendingMessages.put(seq, handle);

        return handle;
    }

    // ============================================================
    // BROADCAST
    // ============================================================

    @Override
    public Map<String, SendHandle> broadcast(Set<String> destinationIds, byte[] payload) {

        long seq = localSequenceCounter.incrementAndGet();

        Map<String, SendHandle> handles = new ConcurrentHashMap<>();

        for (String dest : destinationIds) {

            Envelope.Builder builder = Envelope.newBuilder()
                    .setSenderId(config.getSelfId())
                    .setSequenceNumber(seq)
                    .setPayload(ByteString.copyFrom(payload));

            Envelope envelope;

            if (authenticator != null && authenticator.shouldAuthenticate(dest)) {
                envelope = authenticator.encrypt(dest, builder);
            } else {
                envelope = builder.build();
            }

            SendHandle handle = stubbornLink.send(dest, envelope.toByteArray());
            handles.put(dest, handle);
        }

        pendingBroadcasts.put(seq, handles);
        return handles;
    }

    // ============================================================
    // RECEIVE
    // ============================================================

    private void handleIncomingMessage(String senderId, byte[] data) {

        try {
            Envelope envelope = Envelope.parseFrom(data);

            if (authenticator != null && authenticator.shouldAuthenticate(senderId)) {

                Envelope processed = authenticator.decrypt(envelope);

                if (processed == null) {
                    return; // discard (invalid or handshake)
                }

                envelope = processed;
            }

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

        SendHandle handle = pendingMessages.remove(seq);

        if (handle != null) {
            handle.cancel();
            return;
        }

        handleBroadcastAck(seq, senderId);
    }

    private void handleBroadcastAck(long seq, String sender) {

        Map<String, SendHandle> map = pendingBroadcasts.get(seq);
        if (map == null) return;

        SendHandle handle = map.remove(sender);

        if (handle != null) {
            handle.cancel();
        }

        if (map.isEmpty()) {
            pendingBroadcasts.remove(seq);
        }
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