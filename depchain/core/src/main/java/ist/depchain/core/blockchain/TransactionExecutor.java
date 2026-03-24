package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;

import ist.depchain.common.Transaction;

/**
 * Executes transactions against a provided world state.
 *
 * Currently supports native DepCoin transfers with gas accounting.
 * EVM contract deployment/calls will be added in Step 6.
 *
 * Gas rules:
 * fee = min(gasPrice * gasLimit, gasPrice * gasUsed)
 * If gasUsed > gasLimit -> tx aborted, gas NOT refunded.
 * Fees are deducted from sender's native DepCoin balance.
 * Fees are credited to the block proposer (leader).
 */
public class TransactionExecutor {

    /** Fixed gas cost for a native DepCoin transfer (no EVM involved). */
    private static final BigInteger NATIVE_TRANSFER_GAS = BigInteger.valueOf(21_000);

    public TransactionReceipt execute(DepChainWorldState state, Transaction tx, Address proposer) {
        byte[] txHash = tx.txHash();
        Address sender = tx.getFrom();

        BigInteger upfrontCost = tx.getMaxUpfrontCost();
        if (upfrontCost.signum() < 0) {
            return TransactionReceipt.failure(txHash, BigInteger.ZERO, BigInteger.ZERO, "negative upfront cost");
        }

        if (state.getBalance(sender).compareTo(upfrontCost) < 0) {
            return TransactionReceipt.failure(txHash, BigInteger.ZERO, BigInteger.ZERO,
                    "insufficient balance for upfront cost");
        }

        // Reserve max upfront cost immediately.
        state.subtractBalance(sender, upfrontCost);

        // Nonce always advances for an accepted-on-chain transaction, even if execution
        // fails deterministically.
        state.incrementNonce(sender);

        if (tx.isContractDeployment()) {
            return executeContractDeployment(state, tx, txHash, proposer);
        } else if (tx.isContractCall()) {
            return executeContractCall(state, tx, txHash, proposer);
        } else {
            return executeNativeTransfer(state, tx, txHash, proposer);
        }
    }

    private TransactionReceipt executeNativeTransfer(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {
        BigInteger gasUsed = NATIVE_TRANSFER_GAS;
        BigInteger gasLimit = tx.getGasLimit();
        BigInteger gasPrice = tx.getGasPrice();

        BigInteger feeByLimit = gasPrice.multiply(gasLimit);
        BigInteger feeByUsed = gasPrice.multiply(gasUsed);

        if (gasLimit.compareTo(gasUsed) < 0) {
            // Out of gas: value transfer does not happen, gas is not refunded.
            // We already deducted (value + maxFee) upfront.
            BigInteger fee = feeByLimit;
            creditFee(state, proposer, fee);

            // Refund the value part only.
            if (tx.getValue().signum() > 0) {
                state.addBalance(tx.getFrom(), tx.getValue());
            }

            return TransactionReceipt.failure(txHash, gasUsed, fee, "out of gas: native transfer requires " + NATIVE_TRANSFER_GAS + " gas");
        }

        Address receiver = tx.getTo();
        if (!state.accountExists(receiver)) {
            state.createEOA(receiver, 0, BigInteger.ZERO);
        }
        state.addBalance(receiver, tx.getValue());

        BigInteger fee = feeByLimit.min(feeByUsed);
        creditFee(state, proposer, fee);

        BigInteger refund = feeByLimit.subtract(fee);
        if (refund.signum() > 0) {
            state.addBalance(tx.getFrom(), refund);
        }

        return TransactionReceipt.success(txHash, gasUsed, fee);
    }

    private TransactionReceipt executeContractDeployment(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {
        BigInteger fee = tx.getMaxFee();
        creditFee(state, proposer, fee);

        // Value is not transferred anywhere yet; refund it fully until Step 6.
        if (tx.getValue().signum() > 0) {
            state.addBalance(tx.getFrom(), tx.getValue());
        }

        return TransactionReceipt.failure(txHash, BigInteger.ZERO, fee,
                "contract deployment not implemented yet");
    }

    private TransactionReceipt executeContractCall(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {

        BigInteger fee = tx.getMaxFee();
        creditFee(state, proposer, fee);

        // For now, refund value because call execution is not implemented.
        if (tx.getValue().signum() > 0) {
            state.addBalance(tx.getFrom(), tx.getValue());
        }

        return TransactionReceipt.failure(txHash, BigInteger.ZERO, fee, "contract call not implemented yet");
    }

    private void creditFee(DepChainWorldState state, Address proposer, BigInteger fee) {
        if (proposer == null || fee == null || fee.signum() <= 0) {
            return;
        }
        if (!state.accountExists(proposer)) {
            state.createEOA(proposer, 0, BigInteger.ZERO);
        }
        state.addBalance(proposer, fee);
    }
}