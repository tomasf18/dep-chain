package ist.depchain.core.hotstuff.tsignatures;

import java.nio.file.*;
import com.herumi.bls.Bls;

/*
    BLS Manager for loading the native BLS library and initializing it. 
 */
public class BLSManager {
    private static boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;
        try {
            // find the .so bls lib relative to the working directory (core/)
            Path nativePath = Path.of(getNativeLibraryPath())
                .toAbsolutePath()
                .normalize();

            System.load(nativePath.toString()); // absolute path

            Bls.init(5); // 5 = BLS12_381
            initialized = true;
            System.out.println("[BLS | INFO] - BLS12-381 initialized");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BLS: " + e.getMessage(), e);
        }
    }

    private static String getNativeLibraryPath(){
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        String folder;
        String fileName;

        if(osName.contains("mac")){
            fileName = "libblsjava.dylib";
            folder = osArch.contains("aarch64") ? "macos-arm64" : "macos-x86_64";
        }
        else if(osName.contains("win")){
            fileName = "libblsjava.dll";
            folder = "windows-x86_64";
        }
        else{
            fileName = "libblsjava.so";
            folder = "linux-x86_64";
        }
        return "./native/"+folder+"/"+fileName;
    }
}