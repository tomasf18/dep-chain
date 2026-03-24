package ist.depchain.core.blockchain;

import java.security.PublicKey;

import org.hyperledger.besu.datatypes.Address;

import ist.depchain.common.utils.AddressUtils;
import ist.depchain.network.crypto.Crypto;
import ist.depchain.common.Transaction;

/**
 * Validates incoming transactions from clients before they are accepted into the mempool.
 * Checks include:
 * - presence and correctness of signature
 * - sender address matches signer-derived address
 * - sender account exists in world state
 * - nonce is correct
 * - gas price and limit are positive
 * - value is non-negative and sender has sufficient balance to cover value + max gas cost
 * - contract deployments have non-empty bytecode
 */
public class TransactionValidator {

    public record ValidationResult(boolean valid, String error) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult fail(String error) {
            return new ValidationResult(false, error);
        }
    }

    public static ValidationResult validate(
            Transaction tx,
            PublicKey clientPublicKey,
            String signatureAlgorithm,
            DepChainWorldState worldState) {

        if (tx == null) {
            return ValidationResult.fail("missing transaction");
        }

        if (tx.getSignature() == null || tx.getSignature().length == 0) {
            return ValidationResult.fail("missing transaction signature");
        }

        try {
            boolean sigOk = Crypto.verifySignature(
                    tx.toUnsignedBytes(),
                    tx.getSignature(),
                    clientPublicKey,
                    signatureAlgorithm);

            if (!sigOk) {
                return ValidationResult.fail("invalid transaction signature");
            }
        } catch (Exception e) {
            return ValidationResult.fail("signature verification failed: " + e.getMessage());
        }

        Address derived = AddressUtils.deriveAddress(clientPublicKey);
        if (!derived.equals(tx.getFrom())) {
            return ValidationResult.fail("transaction sender does not match signer-derived address");
        }

        if (!worldState.accountExists(tx.getFrom())) {
            return ValidationResult.fail("unknown sender account");
        }

        long expectedNonce = worldState.getNonce(tx.getFrom());
        if (tx.getNonce() != expectedNonce) {
            return ValidationResult.fail("invalid nonce: expected " + expectedNonce + ", got " + tx.getNonce());
        }

        if (tx.getGasPrice().signum() <= 0) {
            return ValidationResult.fail("gasPrice must be > 0");
        }

        if (tx.getGasLimit().signum() <= 0) {
            return ValidationResult.fail("gasLimit must be > 0");
        }

        if (tx.getValue().signum() < 0) {
            return ValidationResult.fail("value must be >= 0");
        }

        if (worldState.getBalance(tx.getFrom()).compareTo(tx.getMaxUpfrontCost()) < 0) {
            return ValidationResult.fail("insufficient balance for value + max gas");
        }

        if (tx.isContractDeployment() && tx.getData().length == 0) {
            return ValidationResult.fail("contract deployment requires non-empty bytecode");
        }

        return ValidationResult.ok();
    }
}