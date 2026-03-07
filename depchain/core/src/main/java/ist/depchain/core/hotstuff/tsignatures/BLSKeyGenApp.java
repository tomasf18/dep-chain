package ist.depchain.core.hotstuff.tsignatures;

import ist.depchain.common.utils.Config;

import com.herumi.bls.SecretKey;
import com.herumi.bls.SecretKeyVec;
import java.nio.file.Files;
import java.nio.file.Path;

/* 
    NOTE: Only for key generation, not used at runtime (only run once). This is a standalone app to generate BLS keys for the replicas.
    How to run: 
    0) Go to pom and change 'org.apache.maven.plugins' and 'org.codehaus.mojo' main class from 'ist.depchain.core.ServerApp' to 'ist.depchain.core.hotstuff.tsignatures.BLSKeyGenApp' 
    1) mvn clean compile (inside core/)
    2) mvn exec:java -Dexec.args="path/to/config.json"
    3) Change the main class back to 'ist.depchain.core.ServerApp' and run the server as normal. The generated keys will be in core/keystore/s0, s1, s2, s3 (one folder per replica).
*/
public class BLSKeyGenApp {

    private static Config config;

    public static void main(String[] args) throws Exception {
        config = Config.loadConfiguration(args[0], "raw"); // selfId doesn't matter for keygen, just need access to config for N and f

        BLSManager.init();

        int n = config.getN(), f = config.getF(), threshold = f + 1;

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