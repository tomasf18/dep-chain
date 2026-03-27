package ist.depchain.common.utils;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;

public class Crypto {

    static final int HMAC_TAG_LEN = 32; // HmacSHA256 output length in bytes

    private Crypto() {
    }

    /**
     * Computes HMAC-SHA256 over (seq_number_bytes || plaintext) and returns
     * {@code plaintext || tag}. The seq binds the tag to the message's
     * position in the stream, preventing replay under a different sequence number.
     */
    public static byte[] authenticate(long sequence_number, byte[] plaintext, SecretKey key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        mac.update(longToBytes(sequence_number));
        byte[] tag = mac.doFinal(plaintext);

        byte[] output = new byte[plaintext.length + tag.length];
        System.arraycopy(plaintext, 0, output, 0, plaintext.length);
        System.arraycopy(tag, 0, output, plaintext.length, tag.length);
        return output;
    }

    /**
     * Verifies the HMAC-SHA256 tag (bound to sequence_number) in {@code tagged} and returns
     * the original plaintext, or null if the tag is missing or does not match.
     */
    public static byte[] verifyAuthenticity(long sequence_number, byte[] tagged, SecretKey key) throws Exception {
        if (tagged.length <= HMAC_TAG_LEN) return null;
        byte[] plaintext = Arrays.copyOfRange(tagged, 0, tagged.length - HMAC_TAG_LEN);
        byte[] receivedTag = Arrays.copyOfRange(tagged, tagged.length - HMAC_TAG_LEN, tagged.length);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        mac.update(longToBytes(sequence_number));
        byte[] expectedTag = mac.doFinal(plaintext);
        if (!MessageDigest.isEqual(expectedTag, receivedTag)) {
            return null;
        }
        return plaintext;
    }

    public static byte[] sign(byte[] data, PrivateKey privateKey, String algorithm) throws Exception {
        Signature sig = Signature.getInstance(algorithm);
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    public static boolean verifySignature(byte[] data, byte[] signature, PublicKey publicKey, String algorithm) throws Exception {
        Signature sig = Signature.getInstance(algorithm);
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
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
