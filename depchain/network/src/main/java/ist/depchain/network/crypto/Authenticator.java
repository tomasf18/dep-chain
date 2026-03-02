package ist.depchain.network.crypto;

import com.google.protobuf.ByteString;
import ist.depchain.common.Envelope;
import ist.depchain.common.Handshake;
import ist.depchain.common.utils.Config;
import ist.depchain.network.interfaces.MessageAuthenticator;
import ist.depchain.network.interfaces.Link;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.ByteArrayOutputStream;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Authenticator implements MessageAuthenticator {

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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean shouldAuthenticate(String peerId) {
        return config.getBlockChainServers().containsKey(peerId);
    }

    @Override
    public Envelope encrypt(String destinationId, Envelope.Builder builder) {

        SecretKey key = sessionKeys.get(destinationId);

        if (key == null) {
            initiateHandshake(destinationId);
            throw new RuntimeException("Session not established with " + destinationId);
        }

        try {
            byte[] encrypted = encrypt(builder.getPayload().toByteArray(), key);
            return builder
                    .setPayload(ByteString.copyFrom(encrypted))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Envelope decrypt(Envelope envelope) {

        String senderId = envelope.getSenderId();

        if (envelope.hasHandshake()) {
            processHandshake(senderId, envelope.getHandshake());
            return null; // handshake nunca sobe para PerfectLink
        }

        SecretKey sessionKey = sessionKeys.get(senderId);

        if (sessionKey == null) {
            // ainda não há sessão → descartar (ou poderias iniciar handshake aqui)
            return null;
        }

        try {

            byte[] encrypted = envelope.getPayload().toByteArray();

            if (encrypted.length < 12) {
                return null; // inválido
            }

            byte[] iv = Arrays.copyOfRange(encrypted, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, 12, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);

            return Envelope.newBuilder(envelope)
                    .setPayload(ByteString.copyFrom(plaintext))
                    .build();

        } catch (AEADBadTagException e) {
            // GCM authentication failed (mensagem alterada ou chave errada)
            return null;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void initiateHandshake(String peerId) {
        Handshake handshake = Handshake.newBuilder()
                .setEcdhPublicKey(ByteString.copyFrom(myKeyPair.getPublic().getEncoded()))
                .build();

        Envelope envelope = Envelope.newBuilder()
                .setSenderId(config.getSelfId())
                .setHandshake(handshake)
                .build();

        fairLossLink.send(peerId, envelope.toByteArray());
    }

    private void processHandshake(String peerId, Handshake handshake) {
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey peerPublic = kf.generatePublic(
                    new X509EncodedKeySpec(handshake.getEcdhPublicKey().toByteArray()));

            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(myKeyPair.getPrivate());
            ka.doPhase(peerPublic, true);

            byte[] sharedSecret = ka.generateSecret();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(sharedSecret);

            SecretKey sessionKey = new SecretKeySpec(keyBytes, 0, 16, "AES");

            sessionKeys.put(peerId, sessionKey);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] encrypt(byte[] plaintext, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);

        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(iv);
        output.write(ciphertext);
        return output.toByteArray();
    }

    private byte[] decrypt(byte[] input, SecretKey key) throws Exception {
        byte[] iv = Arrays.copyOfRange(input, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(input, 12, input.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

        return cipher.doFinal(ciphertext);
    }
}