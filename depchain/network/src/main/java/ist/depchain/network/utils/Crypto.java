package ist.depchain.network.utils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public class Crypto {

    private Crypto() {
    }

    public static byte[] sign(Config config, byte[] data) throws Exception {
        Signature sig = Signature.getInstance(config.getSignatureAlgorithm());
        PrivateKey privateKey = KeyLoader.loadPrivateKey(config);
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    public static boolean verify(Config config, String signerId, byte[] data, byte[] signature) {
        try {
            PublicKey signerPublicKey = KeyLoader.loadPublicKey(config, signerId);
            if (signerPublicKey == null) {
                System.err.println("Crypto: No public key found for signer " + signerId);
                return false;
            }
            Signature sig = Signature.getInstance(config.getSignatureAlgorithm());
            sig.initVerify(signerPublicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            System.out.println("Crypto: Exception during signature verification for signer " + signerId + ": " + e.getMessage());
            return false;
        }
    }
}
