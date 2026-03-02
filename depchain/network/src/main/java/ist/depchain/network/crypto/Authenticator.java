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

    private static final int HMAC_TAG_LEN = 32; // HmacSHA256 output length in bytes

    private final Config config;
    private final Link fairLossLink;

    private final KeyPair myKeyPair;
    private final Map<String, SecretKey> sessionKeys = new ConcurrentHashMap<>();

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

                if (!pending.isEmpty()) {
                    try {
                        Thread.sleep(2000); // retry every 2s for peers not yet up
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            System.out.println("All handshakes complete.");
        });

        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public boolean shouldAuthenticate(String peerId) {
        return config.getBlockChainServers().containsKey(peerId);
    }

    /**
     * Appends an HMAC-SHA256 tag over (seq || payload) to the payload.
     * seq is taken from the builder so the tag is bound to the sequence number.
     */
    @Override
    public Envelope encrypt(String destinationId, Envelope.Builder builder) {
        SecretKey key = sessionKeys.get(destinationId);
        try {
            byte[] signed = sign(builder.getSequenceNumber(), builder.getPayload().toByteArray(), key);
            return builder
                    .setPayload(ByteString.copyFrom(signed))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verifies the HMAC-SHA256 tag (bound to the envelope's sequence number) and
     * strips it, returning the original payload.
     * Returns null on handshake messages, missing session key, or tag mismatch.
     */
    @Override
    public Envelope decrypt(Envelope envelope) {

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

            if (tagged.length <= HMAC_TAG_LEN) {
                return null; // invalid — no room for payload
            }

            byte[] plaintext = Arrays.copyOfRange(tagged, 0, tagged.length - HMAC_TAG_LEN);
            byte[] receivedTag = Arrays.copyOfRange(tagged, tagged.length - HMAC_TAG_LEN, tagged.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(sessionKey);
            mac.update(longToBytes(envelope.getSequenceNumber())); // bind tag to seq for freshness
            byte[] expectedTag = mac.doFinal(plaintext);

            if (!MessageDigest.isEqual(expectedTag, receivedTag)) {
                return null;
            }

            return Envelope.newBuilder(envelope)
                    .setPayload(ByteString.copyFrom(plaintext))
                    .build();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
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
        }
    }

    /**
     * Signs {@code payload} bound to {@code seq} and returns {@code payload || HMAC(seq || payload)}.
     * The seq must match what will be placed in the Envelope header so the tag is
     * tied to both the content and its position in the message stream.
     */
    public byte[] signPayload(String destinationId, long seq, byte[] payload) {
        SecretKey key = sessionKeys.get(destinationId);
        try {
            return sign(seq, payload, key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Computes HMAC-SHA256 over (seq_bytes || plaintext) and returns
     * {@code plaintext || tag}.  The seq binds the tag to the message's
     * position in the stream, preventing replay under a different sequence number.
     */
    private byte[] sign(long seq, byte[] plaintext, SecretKey key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        mac.update(longToBytes(seq));
        byte[] tag = mac.doFinal(plaintext);

        byte[] output = new byte[plaintext.length + tag.length];
        System.arraycopy(plaintext, 0, output, 0, plaintext.length);
        System.arraycopy(tag, 0, output, plaintext.length, tag.length);
        return output;
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return b;
    }
}
