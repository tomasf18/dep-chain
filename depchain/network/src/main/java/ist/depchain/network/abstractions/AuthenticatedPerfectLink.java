package ist.depchain.network.abstractions;

import com.google.protobuf.ByteString;
import ist.depchain.common.Envelope;
import ist.depchain.common.utils.Config;
import ist.depchain.network.crypto.Authenticator;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated Perfect Link: composes PerfectLink (ordering, dedup, ACKs) with
 * an Authenticator (ECDH session key establishment + HMAC-SHA256 per-message auth).
 *
 * Layering:
 *   UdpFairLossLink → StubbornLink → [AuthenticatedPerfectLink intercepts here]
 *                                           → PerfectLink (pure ordering / ACK)
 *                                                 → application
 *
 * Send path:  sign payload → PerfectLink.send()
 * Recv path:  parse Envelope → route handshakes to Authenticator
 *                             → pass ACKs through
 *                             → verify HMAC → PerfectLink.ingest()
 */
public class AuthenticatedPerfectLink implements Link {

    private final Config config;
    private final PerfectLink pl;
    private final Authenticator authenticator;

    // Per-destination outgoing sequence counter, kept in sync with PL's counter.
    // Owned here so we can include the seq in the HMAC before calling pl.sendWithSeq().
    private final Map<String, AtomicLong> outSeq = new ConcurrentHashMap<>();

    public AuthenticatedPerfectLink(Config config,
                                    StubbornLink stubbornLink,
                                    UdpFairLossLink fairLossLink,
                                    Authenticator authenticator) {
        this.config = config;
        this.authenticator = authenticator;
        this.pl = new PerfectLink(config, stubbornLink, fairLossLink);

        // Override the receiver that PerfectLink registered so APL intercepts first.
        stubbornLink.registerReceiver(this::handleRaw);
    }

    // ============================================================
    // SEND
    // ============================================================

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        if (authenticator.shouldAuthenticate(destinationId)) {
            long seq = outSeq.computeIfAbsent(destinationId, k -> new AtomicLong(0))
                             .incrementAndGet();
            byte[] signed = authenticator.signPayload(destinationId, seq, payload);
            return pl.sendWithSeq(destinationId, signed, seq);
        }
        return pl.send(destinationId, payload);
    }

    @Override
    public Map<String, SendHandle> broadcast(Set<String> destinationIds, byte[] payload) {
        // Each destination has its own per-dest seq counter in PL, so looping send()
        // is safe — no gaps are created for other peers.
        Map<String, SendHandle> handles = new HashMap<>();
        for (String dest : destinationIds) {
            handles.put(dest, send(dest, payload));
        }
        return handles;
    }

    // ============================================================
    // RECEIVE — intercepts before PerfectLink
    // ============================================================

    private void handleRaw(String senderId, byte[] data) {
        try {
            if (!authenticator.shouldAuthenticate(senderId)) {
                pl.ingest(senderId, data);
                return;
            }

            Envelope envelope = Envelope.parseFrom(data);

            if (envelope.hasAck()) {
                // ACKs are unauthenticated by design; pass through so PL can cancel retransmissions.
                pl.ingest(senderId, data);
                return;
            }

            // Simulate tampering of authenticated payload messages only.
            // Applied fresh on each arrival so retransmissions are independent attempts.
            if (envelope.hasPayload() && Math.random() < config.getTamperProbability()) {
                byte[] payload = envelope.getPayload().toByteArray();
                payload[(int) (Math.random() * payload.length)] ^= (byte) 0xFF;
                envelope = Envelope.newBuilder(envelope)
                        .setPayload(ByteString.copyFrom(payload))
                        .build();
            }

            // authenticator.decrypt() processes handshakes as a side-effect (returns null)
            // and verifies the HMAC tag for payload envelopes.
            Envelope processed = authenticator.decrypt(envelope);
            if (processed == null) {
                return; // handshake message or tampered payload — discard
            }

            pl.ingest(senderId, processed.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ============================================================
    // LIFECYCLE / DELEGATION
    // ============================================================

    @Override
    public void registerReceiver(MessageHandler handler) {
        pl.registerReceiver(handler);
    }

    @Override
    public void start() {
        pl.start();
    }

    @Override
    public void stop() {
        pl.stop();
    }
}
