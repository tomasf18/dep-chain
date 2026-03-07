# BLS Threshold Signature Integration Guide

Integration of [herumi/bls](https://github.com/herumi/bls) (BLS12-381) into the DepChain HotStuff consensus project on Fedora Linux.

---

## Prerequisites

```bash
sudo dnf install -y git gcc g++ make cmake gmp gmp-devel java-21-openjdk-devel
```

Confirm your JDK path:

```sh
java -version
echo $JAVA_HOME
# Expected: /usr/lib/jvm/java-21-openjdk
```

---

## Step 1 - Clone and Build the Native Library

```sh
cd ~   # or wherever you keep dependencies outside the project

git clone --recursive https://github.com/herumi/bls
cd bls
```

Build the static archive and object files with `BLS_ETH=1` (BLS12-381 mode). The critical flag here is `BLS_ETH=1` - without it the compiled-time variable will mismatch the Java wrapper and cause a `bad curveType` error at runtime.

```sh
make -f Makefile.onelib BLS_ETH=1 LIB_DIR=lib
```

Rebuild `bls_c384_256.o` with `BLS_ETH=1` explicitly (the Makefile.onelib omits it from this object):

```sh
g++ -c src/bls_c384_256.cpp -o obj/bls_c384_256.o \
  -O3 -DNDEBUG \
  -DBLS_ETH=1 \
  -DMCL_DONT_USE_OPENSSL \
  -DMCL_SIZEOF_UNIT=8 \
  -DCYBOZU_DONT_USE_EXCEPTION \
  -DCYBOZU_DONT_USE_STRING \
  -D_FORTIFY_SOURCE=0 \
  -I./include -I./mcl/include \
  -std=c++14 -fpic \
  -DMCL_USE_LLVM=1 \
  -DMCL_MSM=1
```

Build the JNI shared library (`libblsjava.so`) using the repo's own Makefile, pointing it at the correct JDK include path:

```sh
make -C ffi/java BLS_ETH=1 JAVA_INC_DIR=$JAVA_HOME/include
```

Verify the output:

```sh
ls -lh lib/libblsjava.so
# Expected: ~7.4M libblsjava.so
```

Verify JNI symbols are present:

```sh
nm -D lib/libblsjava.so | grep "Java_com_herumi_bls_BlsJNI_init"
# Must print a line with the symbol
```

---

## Step 2 - Copy Artifacts into the Project

```sh
# Copy the shared library
cp lib/libblsjava.so ~/{path-to}/dep-chain/depchain/native/linux-x86_64/

# Copy the SWIG-generated Java wrapper classes
cp -r ffi/java/com \
      ~/{path-to}/dep-chain/depchain/core/src/main/java/

# Remove Msg.java - it references JNI methods not present in this build
rm ~/{path-to}/dep-chain/depchain/core/src/main/java/com/herumi/bls/Msg.java
```

Final native directory layout:

```
depchain/
|- native/
    |- linux-x86_64/
        |- libblsjava.so
```

Final Java sources layout:

```
core/src/main/java/
|- com/herumi/bls/
    |- Bls.java
    |- BlsConstants.java
    |- BlsJNI.java
    |- PublicKey.java
    |- PublicKeyVec.java
    |- SecretKey.java
    |- SecretKeyVec.java
    |- Signature.java
    |- SignatureVec.java
    |- SWIGTYPE_*.java
```

---

## Step 3 - Maven Configuration (`core/pom.xml`)

Add the JNA dependency (required by the SWIG wrapper):

```xml
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna</artifactId>
    <version>5.18.1</version>
    <scope>compile</scope>
</dependency>
```

Configure the exec plugin to set `LD_LIBRARY_PATH`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.6.3</version>
    <configuration>
        <mainClass>ist.depchain.core.ServerApp</mainClass>
        <environmentVariables>
            <LD_LIBRARY_PATH>${project.basedir}/../native/linux-x86_64</LD_LIBRARY_PATH>
        </environmentVariables>
    </configuration>
</plugin>
```

---

## Step 4 - Java Integration Classes

### `BLSManager.java`

Loads the native library using an absolute path (bypasses `java.library.path` entirely):

```java
package ist.depchain.core.hotstuff.tsignatures;

import java.nio.file.*;
import com.herumi.bls.Bls;

Path nativePath = Path.of("../native/linux-x86_64/libblsjava.so").toAbsolutePath().normalize();
System.load(nativePath.toString());
Bls.init(5); // 5 = BLS12_381
```

### `BLSThresholdSig.java`

Encapsulates partial sign, combine, and verify:

```java
package ist.depchain.core.hotstuff.tsignatures;

import com.herumi.bls.*;

public byte[] partialSign(byte[] message) {
        Signature sig = secretShare.sign(message);
        byte[] sigBytes = sig.serialize();
        byte[] result = new byte[4 + sigBytes.length];
        result[0] = (byte)(replicaIndex >> 24);
        result[1] = (byte)(replicaIndex >> 16);
        result[2] = (byte)(replicaIndex >> 8);
        result[3] = (byte)(replicaIndex);
        System.arraycopy(sigBytes, 0, result, 4, sigBytes.length);
        return result;
}

public byte[] combine(Collection<byte[]> encodedPartialSigs) {
    if (encodedPartialSigs.size() < threshold)
        throw new IllegalArgumentException("Need at least " + threshold + " partial sigs");

    SignatureVec sigVec = new SignatureVec();
    SecretKeyVec idVec = new SecretKeyVec();

    for (byte[] encoded : encodedPartialSigs) {
        int index = ((encoded[0] & 0xFF) << 24) | ((encoded[1] & 0xFF) << 16)
                  | ((encoded[2] & 0xFF) << 8)  |  (encoded[3] & 0xFF);
        byte[] sigBytes = new byte[encoded.length - 4];
        System.arraycopy(encoded, 4, sigBytes, 0, sigBytes.length);

        Signature partialSig = new Signature();
        partialSig.deserialize(sigBytes);
        sigVec.add(partialSig);
        
        SecretKey id = new SecretKey();
        id.setInt(index);
        idVec.add(id);
    }

    return Bls.recover(sigVec, idVec).serialize();
}

public boolean verify(byte[] message, byte[] thresholdSigBytes) {
    try {
        Signature sig = new Signature();
        sig.deserialize(thresholdSigBytes);
        return sig.verify(masterPubKey, message);
    } catch (Exception e) {
        System.err.println("[BLS | ERROR] - Verify failed: " + e.getMessage());
        return false;
    }
}
```

### `BLSKeyGenApp.java`

Run once to generate key shares for all replicas:

```java
package ist.depchain.core;

import com.herumi.bls.*;
import ist.depchain.core.hotstuff.tsignatures.BLSManager;
import java.nio.file.*;

BLSManager.init();
int n = 4, f = 1, threshold = f + 1;
SecretKeyVec msk = new SecretKeyVec();
for (int i = 0; i < threshold; i++) {
    SecretKey coeff = new SecretKey();
    coeff.setByCSPRNG();
    msk.add(coeff);
}
byte[] masterPubBytes = msk.get(0).getPublicKey().serialize();

for (int i = 1; i <= n; i++) {
    SecretKey id = new SecretKey();
    id.setInt(i);
    SecretKey share = new SecretKey();
    share.share(msk, id);

    Path dir = Path.of("keystore", "s" + (i - 1));
    Files.createDirectories(dir);
    Files.write(dir.resolve("bls_master_pub.key"),   masterPubBytes);
    Files.write(dir.resolve("bls_secret_share.key"), share.serialize());
    System.out.println("[KEYGEN] Written share for s" + (i-1) + " (id=" + i + ")");
}
```

---

## Step 6 - Generate Keys and Run

Keys must be generated once before starting any replica.
Then start replicas normally

Expected startup output:

```
[BLS | INFO] - BLS12-381 initialized
[BLS | INFO] - Loaded BLS keys for replica index 1
[SERVER_APP | INFO] Successfully started
```