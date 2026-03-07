package ist.depchain.core.hotstuff.tsignatures;

import com.herumi.bls.*;
import java.nio.file.*;

public class BLSKeyGenApp {
    public static void main(String[] args) throws Exception {
        BLSManager.init();

        int n = 4, f = 1, threshold = f + 1; // k=2, n=4

        // Generate master secret polynomial: k random coefficients
        SecretKeyVec msk = new SecretKeyVec();
        for (int i = 0; i < threshold; i++) {
            SecretKey coeff = new SecretKey();
            coeff.setByCSPRNG();
            msk.add(coeff);
        }

        // Master public key = public key of the constant term (a0)
        byte[] masterPubBytes = msk.get(0).getPublicKey().serialize();

        // Derive one share per replica (1-indexed)
        for (int i = 1; i <= n; i++) {
            SecretKey id = new SecretKey();
            id.setInt(i); // replica i gets id=i

            SecretKey share = new SecretKey();
            share.share(msk, id); // evaluate polynomial at id

            String replicaId = "s" + (i - 1); // s0, s1, s2, s3
            Path dir = Path.of("keystore", replicaId);
            Files.createDirectories(dir);

            Files.write(dir.resolve("bls_master_pub.key"),   masterPubBytes);
            Files.write(dir.resolve("bls_secret_share.key"), share.serialize());

            System.out.println("[KEYGEN] Share written for " + replicaId + " (id=" + i + ") -> " + dir);
        }

        System.out.println("[KEYGEN] Done.");
    }
}