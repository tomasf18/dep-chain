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
 * - value is non-negative
 * - contract deployments have non-empty bytecode
 */
public class TransactionValidator {

    private TransactionValidator() {}

    public static ValidationResult validate(Transaction tx, PublicKey clientPublicKey, String signatureAlgorithm, DepChainWorldState worldState, Set<Long> pendingNonces) {

        if (tx == null) {
            return ValidationResult.fail("missing transaction");
        }

        ValidationResult signatureResult = validateSignatureAndSender(tx, clientPublicKey, signatureAlgorithm);
        if (!signatureResult.isValid()) {
            return signatureResult;
        }

        ValidationResult senderResult = validateSenderState(tx, worldState);
        if (!senderResult.isValid()) {
            return senderResult;
        }

        ValidationResult nonceResult = validateNonce(tx, worldState, pendingNonces);
        if (!nonceResult.isValid()) {
            return nonceResult;
        }

        ValidationResult gasAndValueResult = validateGasAndValue(tx);
        if (!gasAndValueResult.isValid()) {
            return gasAndValueResult;
        }

        return validateContractShape(tx);
    }

    private static ValidationResult validateSignatureAndSender(Transaction tx, PublicKey clientPublicKey, String signatureAlgorithm) {
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

        return ValidationResult.ok();
    }

    private static ValidationResult validateSenderState(Transaction tx, DepChainWorldState worldState) {
        if (!worldState.accountExists(tx.getFrom())) {
            return ValidationResult.fail("unknown sender account");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateNonce(Transaction tx, DepChainWorldState worldState, Set<Long> pendingNonces) {
        long committedNonce = worldState.getNonce(tx.getFrom());
        long txNonce = tx.getNonce();

        if (txNonce < committedNonce) {
            return ValidationResult.fail("invalid nonce: expected " + committedNonce + " or higher, but got " + txNonce);
        }

        if (pendingNonces != null && pendingNonces.contains(txNonce)) {
            return ValidationResult.fail("invalid nonce: nonce " + txNonce + " already pending");
        }

        return ValidationResult.ok();
    }

    private static ValidationResult validateGasAndValue(Transaction tx) {
        if (tx.getGasPrice().signum() <= 0) {
            return ValidationResult.fail("gasPrice must be > 0");
        }

        if (tx.getGasLimit().signum() <= 0) {
            return ValidationResult.fail("gasLimit must be > 0");
        }

        if (tx.getValue().signum() < 0) {
            return ValidationResult.fail("value must be >= 0");
        }

        return ValidationResult.ok();
    }

    private static ValidationResult validateContractShape(Transaction tx) {
        if (tx.isNativeBalanceQuery()) {
            if (tx.getValue().signum() != 0) {
                return ValidationResult.fail("native balance query must not transfer native value");
            }
            if (tx.getNativeBalanceQueryTarget() == null) {
                return ValidationResult.fail("native balance query missing target address");
            }
            return ValidationResult.ok();
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