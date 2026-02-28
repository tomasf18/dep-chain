package ist.depchain.network.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class KeyLoader {
    
    private KeyLoader() {
    }

    public static PrivateKey loadPrivateKey(Config config) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pem = Files.readString(Paths.get(config.getSelfPrivateKeyPathString()))
                .replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static PublicKey loadPublicKey(Config config, String peerId) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pem = Files.readString(Paths.get(config.getTrustedProcessKeyPathString(peerId)))
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }
}