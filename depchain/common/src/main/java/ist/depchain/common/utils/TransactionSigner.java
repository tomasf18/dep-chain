package ist.depchain.common.utils;

import java.security.PrivateKey;

import ist.depchain.common.Transaction;

public final class TransactionSigner {
    private TransactionSigner() {}

    public static Transaction sign(Transaction unsignedTx, PrivateKey privateKey, String signatureAlgorithm) {
        try {
            byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), privateKey, signatureAlgorithm);
            return unsignedTx.withSignature(sig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign transaction", e);
        }
    }
}