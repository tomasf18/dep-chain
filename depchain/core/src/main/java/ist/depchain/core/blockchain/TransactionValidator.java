package ist.depchain.core.blockchain;

import java.security.PublicKey;
import java.util.Set;

import org.hyperledger.besu.datatypes.Address;

import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Crypto;
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

    public static ValidationResult validate(Transaction tx, PublicKey clientPublicKey, String signatureAlgorithm, DepChainWorldState worldState, Set<Long> pendingNonces) {

        if (tx == null) {
            return ValidationResult.fail("missing transaction");
        }

        if (tx.getSignature() == null || tx.getSignature().length == 0) {
            return ValidationResult.fail("missing transaction signature");
        }

        try {
            boolean sigOk = Crypto.verifySignature(tx.toUnsignedBytes(), tx.getSignature(), clientPublicKey, signatureAlgorithm);

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

        long committedNonce = worldState.getNonce(tx.getFrom());
        long txNonce = tx.getNonce();
        
        // Check for replays: nonce must be fresh (not already committed)
        if (txNonce < committedNonce) {
            return ValidationResult.fail("invalid nonce: expected " + committedNonce + " or higher, but got " + txNonce);
        }
        
        // Check for duplicates: nonce must not already be pending
        if (pendingNonces != null && pendingNonces.contains(txNonce)) {
            return ValidationResult.fail("invalid nonce: nonce " + txNonce + " already pending");
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

        if ((tx.isContractCall() || tx.isContractDeployment()) && tx.getValue().signum() != 0) {
            return ValidationResult.fail("contract transactions with non-zero native value are not supported");
        }

        return ValidationResult.ok();
    }
}