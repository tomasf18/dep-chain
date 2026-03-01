package ist.depchain.network.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.security.SignatureException;

public class Crypto {

    private Crypto() {
    }

    public static byte[] sign(byte[] data, String keyPath, String signatureAlgorithm) {
        try {
            PrivateKey privateKey = KeyLoader.loadPrivateKey(keyPath);
            if (privateKey == null) {
                throw new IllegalArgumentException("Crypto: No private key found for key path " + keyPath);
            }
            Signature sig = Signature.getInstance(signatureAlgorithm);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            System.out.println("Crypto: Exception during signing with key path " + keyPath + ": " + e.getMessage());
            return null;
        }
    }

    public static boolean verify(byte[] data, byte[] signature, String keyPath, String signatureAlgorithm) {
        try {
            PublicKey signerPublicKey = KeyLoader.loadPublicKey(keyPath);
            if (signerPublicKey == null) {
                System.err.println("Crypto: No public key found for key path " + keyPath);
                return false;
            }
            Signature sig = Signature.getInstance(signatureAlgorithm);
            sig.initVerify(signerPublicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            System.out.println("Crypto: Exception during signature verification for key path " + keyPath + ": " + e.getMessage());
            return false;
        }
    }
}
