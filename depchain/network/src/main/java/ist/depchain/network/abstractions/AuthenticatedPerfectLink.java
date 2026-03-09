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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated Perfect Link: composes PerfectLink (ordering, dedup, ACKs) with
 * an Authenticator (ECDH session key establishment + HMAC-SHA256 per-message auth).
 *
 * Layering:
 *   UdpFairLossLink -> StubbornLink -> [AuthenticatedPerfectLink intercepts here]
 *                                           -> PerfectLink (pure ordering / ACK)
 *                                                 -> application
 *
 * Send path:  sign payload -> PerfectLink.send()
 * Recv path:  parse Envelope -> route handshakes to Authenticator
 *                             -> pass ACKs through
 *                             -> verify HMAC -> PerfectLink.handleIncomingMessage()
 */
public class AuthenticatedPerfectLink implements Link {

    private final Config config;
    private final PerfectLink perfectLink;
    private final Authenticator authenticator;

    public AuthenticatedPerfectLink(Config config, StubbornLink stubbornLink, UdpFairLossLink fairLossLink, Authenticator authenticator) {
        this.config = config;
        this.perfectLink = new PerfectLink(config, stubbornLink, fairLossLink);
        this.authenticator = authenticator;

        // Override the receiver that PerfectLink registered so APL intercepts first.
        stubbornLink.registerReceiver(this::handleIncomingMessage);
    }

    // ============================================================
    // SEND
    // ============================================================

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        if (authenticator.shouldAuthenticate(destinationId)) {
            if (!authenticator.hasSession(destinationId)) {
                return null; // no session yet
            }
            long seq = perfectLink.getOutgoingMessagesSeqNumbers().computeIfAbsent(destinationId, k -> new AtomicLong(0)).incrementAndGet();
            byte[] signed = authenticator.authenticatePayload(destinationId, seq, payload);
            return perfectLink.sendWithSeqNumber(destinationId, signed, seq);
        }
        return perfectLink.send(destinationId, payload);
    }

    @Override
    public Map<String, SendHandle> broadcast(Set<String> destinationIds, byte[] payload) {
        Map<String, SendHandle> handles = new HashMap<>();
        int skipped = 0;
        for (String dest : destinationIds) {
            if (authenticator.shouldAuthenticate(dest) && !authenticator.hasSession(dest)) {
                System.out.println("[APL][BROADCAST] Skipping " + dest + " — no authenticated session yet");
                skipped++;
                continue;
            }
            SendHandle handle = send(dest, payload);
            handles.put(dest, handle);
        }
        if (skipped > 0) {
            System.out.println("[APL][BROADCAST] Skipped " + skipped + "/" + destinationIds.size() + " destinations (no session)");
        }
        return handles;
    }

    // ============================================================
    // RECEIVE — intercepts before PerfectLink
    // ============================================================

    private void handleIncomingMessage(String senderId, byte[] data) {
        try {
            Envelope envelope = Envelope.parseFrom(data);

            // Handshakes must be processed for any sender so sessions can be established
            // before the first authenticated payload arrives.
            if (envelope.hasHandshake()) {
                authenticator.verifyMessageAuthenticity(envelope);
                return;
            }

            if (!authenticator.shouldAuthenticate(senderId)) {
                perfectLink.handleIncomingMessage(senderId, data);
                return;
            }

            if (envelope.hasAck()) {
                // ACKs are unauthenticated by design; pass through so PL can cancel retransmissions.
                perfectLink.handleIncomingMessage(senderId, data);
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
            Envelope processed = authenticator.verifyMessageAuthenticity(envelope);
            if (processed == null) {
                return; // handshake message or tampered payload, discard
            }

            perfectLink.handleIncomingMessage(senderId, processed.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Authenticator getAuthenticator() {
        return authenticator;
    }

    // ============================================================
    // LIFECYCLE / DELEGATION
    // ============================================================

    @Override
    public void registerReceiver(MessageHandler handler) {
        perfectLink.registerReceiver(handler);
    }

    @Override
    public void start() {
        perfectLink.start();
    }

    @Override
    public void stop() {
        perfectLink.stop();
    }
}
