package ist.depchain.network.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public class Crypto {

    private Crypto() {
    }

    public static byte[] sign(byte[] data, String keyPath, String signatureAlgorithm) throws Exception {
        PrivateKey privateKey = KeyLoader.loadPrivateKey(keyPath);
        if (privateKey == null) {
            throw new Exception("Crypto: No private key found for key path " + keyPath);
        }
        Signature sig = Signature.getInstance(signatureAlgorithm);
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
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
        } catch (Exception e) {
            System.out.println("Crypto: Exception during signature verification for key path " + keyPath + ": " + e.getMessage());
            return false;
        }
    }
}
