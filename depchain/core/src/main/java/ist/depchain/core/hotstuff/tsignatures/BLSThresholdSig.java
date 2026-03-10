package ist.depchain.core.hotstuff.tsignatures;

import com.herumi.bls.*;
import java.nio.file.*;
import java.util.Collection;

import ist.depchain.common.utils.Config;

public class BLSThresholdSig {

    private final SecretKey secretShare;
    private final PublicKey masterPubKey;
    private final int replicaIndex; 
    private final int threshold;

    public BLSThresholdSig(Config config) {
        this.replicaIndex = config.selfBlsIndex();
        this.threshold = config.getF() + 1; // f+1 partial sigs needed for threshold sig

        try {
            this.secretShare = new SecretKey();
            this.secretShare.deserialize(Files.readAllBytes(Path.of(config.getBLSSecretSharePathString())));

            this.masterPubKey = new PublicKey();
            this.masterPubKey.deserialize(Files.readAllBytes(Path.of(config.getBLSMasterPubPathString())));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load BLS keys from " + config.getSelfKeysDirectory(), e);
        }

        System.out.println("[BLS | INFO] - Loaded BLS keys for replica index " + replicaIndex);
    }

    /**
     * PARTIAL SIGN: each replica calls this independently in voteMsg().
     * Encoding: [4 bytes big-endian replicaIndex][serialized partial signature]
     */
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

    /**
     * COMBINE: leader calls after collecting threshold partial sigs in createQC().
     * Input: encoded partial sigs (each prefixed with 4-byte replica index).
     */
    public byte[] combine(Collection<byte[]> encodedPartialSigs) {
        if (encodedPartialSigs.size() < threshold) {
            throw new IllegalArgumentException("Need at least " + threshold + " partial sigs, got " + encodedPartialSigs.size());
        }

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

        // Lagrange interpolation over BLS12-381: recovers the master signature
        Signature recovered = Bls.recover(sigVec, idVec);
        return recovered.serialize();
    }

    /**
     * VERIFY: any replica calls this in verifyQC().
     * Verifies the combined threshold signature against the master public key.
     */
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
}