package ist.depchain.network.crypto;

import com.google.protobuf.ByteString;
import ist.depchain.common.Envelope;
import ist.depchain.common.Handshake;
import ist.depchain.common.utils.Config;
import ist.depchain.network.interfaces.MessageAuthenticator;
import ist.depchain.network.interfaces.Link;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Authenticator implements MessageAuthenticator {

    private final Config config;
    private final Link fairLossLink;

    private final KeyPair myKeyPair;
    private final Map<String, SecretKey> sessionKeys = new ConcurrentHashMap<>();
    private final Object handshakesComplete = new Object(); // notification object for proposer loop in BHSCoordinator
    private volatile boolean handshakesReady = false;


    public Authenticator(Config config, Link fairLossLink) {
        this.config = config;
        this.fairLossLink = fairLossLink;

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            this.myKeyPair = kpg.generateKeyPair();
            handshakeAll();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handshakeAll() {
        int quorum = config.getN() - config.getF(); // 2F+1: min peers needed to proceed
        Set<String> targets = new HashSet<>(config.getBlockChainServers().keySet());

        Thread thread = new Thread(() -> {
            Set<String> pending = new HashSet<>(targets);

            while (!pending.isEmpty()) {
                Iterator<String> it = pending.iterator();
                while (it.hasNext()) {
                    String peerId = it.next();
                    if (sessionKeys.containsKey(peerId)) {
                        it.remove();
                    } else {
                        initiateHandshake(peerId);
                    }
                }

                // Signal ready once we have enough session keys to tolerate F crashes
                if (!handshakesReady && sessionKeys.size() >= quorum) {
                    System.out.println("[AUTHENTICATOR | INFO] - Handshakes complete with " + sessionKeys.size() + "/" + targets.size() + " peers (quorum=" + quorum + ").");
                    handshakesReady = true;
                    synchronized (handshakesComplete) {
                        handshakesComplete.notifyAll();
                    }
                }

                if (!pending.isEmpty()) {
                    try {
                        Thread.sleep(200); // retry every 200ms for peers not yet up
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            System.out.println("[AUTHENTICATOR | INFO] - All handshakes complete.");

            if (!handshakesReady) {
                handshakesReady = true;
                synchronized (handshakesComplete) {
                    handshakesComplete.notifyAll();
                }
            }
        });

        thread.setDaemon(true);
        thread.start();
    }

    public void waitForHandshakesComplete() throws InterruptedException {
        synchronized (handshakesComplete) {
            while (!handshakesReady) {
                handshakesComplete.wait();
            }
        }
    }

    @Override
    public boolean shouldAuthenticate(String peerId) {
        return config.getProcesses().containsKey(peerId);
    }

    public boolean hasSession(String peerId) {
        return sessionKeys.containsKey(peerId);
    }

    /**
     * Verifies the HMAC-SHA256 tag (bound to the envelope's sequence number) and
     * strips it, returning the original payload.
     * Returns null on handshake messages, missing session key, or tag mismatch.
     */
    @Override
    public Envelope verifyMessageAuthenticity(Envelope envelope) {

        String senderId = envelope.getSenderId();

        if (envelope.hasHandshake()) {
            processHandshake(senderId, envelope.getHandshake());
            return null; // handshake messages never propagate to PerfectLink
        }

        SecretKey sessionKey = sessionKeys.get(senderId);

        if (sessionKey == null) {
            return null;
        }

        try {
            byte[] tagged = envelope.getPayload().toByteArray();
            byte[] plaintext = Crypto.verifyAuthenticity(envelope.getSequenceNumber(), tagged, sessionKey);
            if (plaintext == null) return null;

            return Envelope.newBuilder(envelope)
                    .setPayload(ByteString.copyFrom(plaintext))
                    .build();

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[AUTHENTICATOR | ERROR] - Failed to verify message authenticity from " + envelope.getSenderId());
            return null;
        }
    }

    /**
     * Signs {@code payload} bound to {@code seq} and returns {@code payload || HMAC(seq || payload)}.
     * The seq must match what will be placed in the Envelope header so the tag is
     * tied to both the content and its position in the message stream.
     */
    public byte[] authenticatePayload(String destinationId, long seq, byte[] payload) {
        SecretKey key = sessionKeys.get(destinationId);
        if (key == null) {
            // Session not yet established — StubbornLink will retry later
            throw new IllegalStateException("Session key not yet available for: " + destinationId);
        }
        try {
            return Crypto.authenticate(seq, payload, key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void initiateHandshake(String peerId) {
        initiateHandshake(peerId, false);
    }

    private void initiateHandshake(String peerId, boolean isReply) {
        Handshake handshake = Handshake.newBuilder()
                .setEcdhPublicKey(ByteString.copyFrom(myKeyPair.getPublic().getEncoded()))
                .setIsReply(isReply)
                .build();

        Envelope envelope = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setHandshake(handshake)
                .build();

        fairLossLink.send(peerId, envelope.toByteArray());
    }

    private void processHandshake(String peerId, Handshake handshake) {

        if (sessionKeys.containsKey(peerId)) {
            return;
        }

        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey peerPublic = kf.generatePublic(
                    new X509EncodedKeySpec(handshake.getEcdhPublicKey().toByteArray()));

            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(myKeyPair.getPrivate());
            ka.doPhase(peerPublic, true);

            byte[] sharedSecret = ka.generateSecret();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(sharedSecret); // 32 bytes

            SecretKey sessionKey = new SecretKeySpec(keyBytes, "HmacSHA256");

            sessionKeys.put(peerId, sessionKey);

            // If we didn't initiate, reply so the peer can derive the same session key
            if (!handshake.getIsReply()) {
                initiateHandshake(peerId, true);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[AUTHENTICATOR | ERROR] - Failed to process handshake from " + peerId);
        }
    }

}
