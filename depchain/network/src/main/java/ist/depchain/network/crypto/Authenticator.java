package ist.depchain.network.crypto;

import com.google.protobuf.ByteString;
import ist.depchain.common.Envelope;
import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.Crypto;
import ist.depchain.network.interfaces.MessageAuthenticator;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Authenticator implements MessageAuthenticator {

    private final Config config;
    private final Map<String, SecretKey> sessionKeys = new ConcurrentHashMap<>();

    public Authenticator(Config config) {
        this.config = config;

        try {
            PrivateKey myPrivateKey = KeyLoader.loadPrivateKey(config.getSelfPrivateKeyPathString());
            if (myPrivateKey == null) {
                throw new RuntimeException("Failed to load private key from " + config.getSelfPrivateKeyPathString());
            }

            for (String peerId : config.getProcesses().keySet()) {
                // Clients don't need session keys with other clients
                if (config.isSelfClient() && config.getProcesses().get(peerId).isClient()
                        && !peerId.equals(config.getSelfId())) continue;

                PublicKey peerPublicKey;
                if (peerId.equals(config.getSelfId())) {
                    // Derive session key with self using own public key
                    peerPublicKey = KeyLoader.loadPublicKey(config.getSelfPublicKeyPathString());
                } else {
                    peerPublicKey = KeyLoader.loadPublicKey(config.getTrustedProcessKeyPathString(peerId));
                }
                if (peerPublicKey == null) {
                    System.out.println("[AUTHENTICATOR | WARN] - No public key found for " + peerId + ", skipping.");
                    continue;
                }

                KeyAgreement ka = KeyAgreement.getInstance("ECDH");
                ka.init(myPrivateKey);
                ka.doPhase(peerPublicKey, true);
                byte[] sharedSecret = ka.generateSecret();

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] keyBytes = digest.digest(sharedSecret);

                sessionKeys.put(peerId, new SecretKeySpec(keyBytes, "HmacSHA256"));
            }

            System.out.println("[AUTHENTICATOR | INFO] - Derived session keys with " + sessionKeys.size() + " peers from static keys.");
        } catch (Exception e) {
            throw new RuntimeException(e);
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
     * Returns null on missing session key or tag mismatch.
     */
    @Override
    public Envelope verifyMessageAuthenticity(Envelope envelope) {

        String senderId = envelope.getSenderId();
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
            System.err.println("[AUTHENTICATOR | ERROR] - Failed to verify message authenticity from " + senderId);
            return null;
        }
    }

    /**
     * Signs {@code payload} bound to {@code seq} and returns {@code payload || HMAC(seq || payload)}.
     */
    public byte[] authenticatePayload(String destinationId, long seq, byte[] payload) {
        SecretKey key = sessionKeys.get(destinationId);
        if (key == null) {
            throw new IllegalStateException("No session key for: " + destinationId);
        }
        try {
            return Crypto.authenticate(seq, payload, key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
