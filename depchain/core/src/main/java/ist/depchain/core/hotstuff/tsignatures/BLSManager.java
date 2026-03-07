package ist.depchain.core.hotstuff.tsignatures;

import java.nio.file.*;

public class BLSManager {
    private static boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;
        try {
            // find the .so bls lib relative to the working directory (core/); tries absolute path first, falls back to System.loadLibrary
            Path nativePath = Path.of("../native/linux-x86_64/libblsjava.so")
                .toAbsolutePath()
                .normalize();

            if (Files.exists(nativePath)) {
                System.load(nativePath.toString()); // absolute path
            } else {
                // fallback: rely on LD_LIBRARY_PATH or java.library.path
                System.loadLibrary("blsjava");
            }

            com.herumi.bls.Bls.init(5); // 5 = BLS12_381
            initialized = true;
            System.out.println("[BLS | INFO] - BLS12-381 initialized");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BLS: " + e.getMessage(), e);
        }
    }
}