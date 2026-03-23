package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;

import ist.depchain.common.Transaction;

/**
 * Executes transactions against the world state.
 *
 * Currently supports native DepCoin transfers with gas accounting.
 * EVM contract deployment/calls will be added in Step 6.
 *
 * Gas rules (from spec):
 *   fee = min(gasPrice * gasLimit, gasPrice * gasUsed)
 *   If gasUsed > gasLimit → tx aborted, gas NOT refunded.
 *   Fees are deducted from sender's native DepCoin balance.
 *   Fees are credited to the block proposer (leader).
 */
public class TransactionExecutor {

    /** Fixed gas cost for a native DepCoin transfer (no EVM involved). */
    private static final BigInteger NATIVE_TRANSFER_GAS = BigInteger.valueOf(21_000);

    private final DepChainWorldState worldState;

    public TransactionExecutor(DepChainWorldState worldState) {
        this.worldState = worldState;
    }

    /**
     * Execute a single transaction, mutating world state.
     *
     * @param tx        the transaction to execute
     * @param proposer  the block proposer who receives gas fees (null = burn fees)
     * @return receipt describing outcome
     */
    public TransactionReceipt execute(Transaction tx, Address proposer) {
        byte[] txHash = tx.txHash();
        Address sender = tx.getFrom();

        // 1. Reserve max upfront cost (value + gasLimit * gasPrice)
        //    Validation already checked this, but guard against edge cases.
        BigInteger maxFee = tx.getMaxFee();
        BigInteger upfrontCost = tx.getMaxUpfrontCost();

        if (worldState.getBalance(sender).compareTo(upfrontCost) < 0) {
            return TransactionReceipt.failure(txHash, BigInteger.ZERO, BigInteger.ZERO,
                    "insufficient balance for upfront cost");
        }

        // Deduct upfront cost (will refund unused gas later)
        worldState.subtractBalance(sender, upfrontCost);

        // 2. Increment sender nonce
        worldState.incrementNonce(sender);

        // 3. Execute based on transaction type
        TransactionReceipt receipt;
        if (tx.isContractDeployment()) {
            receipt = executeContractDeployment(tx, txHash, sender, proposer);
        } else if (tx.isContractCall()) {
            receipt = executeContractCall(tx, txHash, sender, proposer);
        } else {
            receipt = executeNativeTransfer(tx, txHash, sender, proposer);
        }

        return receipt;
    }

    private TransactionReceipt executeNativeTransfer(Transaction tx, byte[] txHash,
                                                     Address sender, Address proposer) {
        BigInteger gasUsed = NATIVE_TRANSFER_GAS;
        BigInteger gasLimit = tx.getGasLimit();
        BigInteger gasPrice = tx.getGasPrice();

        // Check if gas limit is sufficient for a native transfer
        if (gasLimit.compareTo(gasUsed) < 0) {
            // Out of gas: tx aborted, fee = gasLimit * gasPrice (not refunded)
            BigInteger fee = gasPrice.multiply(gasLimit);
            creditFee(proposer, fee);
            // Refund only value (gas is NOT refunded when out of gas)
            worldState.addBalance(sender, tx.getValue());
            return TransactionReceipt.failure(txHash, gasUsed, fee,
                    "out of gas: native transfer requires " + NATIVE_TRANSFER_GAS + " gas");
        }

        // Credit receiver
        Address receiver = tx.getTo();
        if (!worldState.accountExists(receiver)) {
            // Create the receiver account if it doesn't exist (like Ethereum)
            worldState.createEOA(receiver, 0, BigInteger.ZERO);
        }
        worldState.addBalance(receiver, tx.getValue());

        // Calculate actual fee: min(gasPrice*gasLimit, gasPrice*gasUsed)
        BigInteger feeByLimit = gasPrice.multiply(gasLimit);
        BigInteger feeByUsed = gasPrice.multiply(gasUsed);
        BigInteger fee = feeByLimit.compareTo(feeByUsed) < 0 ? feeByLimit : feeByUsed;

        // Credit fee to proposer
        creditFee(proposer, fee);

        // Refund unused gas: upfront was (value + gasLimit*gasPrice), we used (value + fee)
        BigInteger refund = feeByLimit.subtract(fee);
        if (refund.signum() > 0) {
            worldState.addBalance(sender, refund);
        }

        return TransactionReceipt.success(txHash, gasUsed, fee);
    }

    private TransactionReceipt executeContractDeployment(Transaction tx, byte[] txHash,
                                                         Address sender, Address proposer) {
        // Placeholder for Step 6 (Besu EVM integration)
        BigInteger fee = tx.getMaxFee();
        creditFee(proposer, fee);
        // Refund value since we can't deploy yet
        worldState.addBalance(sender, tx.getValue());
        return TransactionReceipt.failure(txHash, tx.getGasLimit(), fee,
                "contract deployment not yet implemented");
    }

    private TransactionReceipt executeContractCall(Transaction tx, byte[] txHash,
                                                   Address sender, Address proposer) {
        // Placeholder for Step 6 (Besu EVM integration)
        BigInteger fee = tx.getMaxFee();
        creditFee(proposer, fee);
        // Refund value since we can't call yet
        worldState.addBalance(sender, tx.getValue());
        return TransactionReceipt.failure(txHash, tx.getGasLimit(), fee,
                "contract calls not yet implemented");
    }

    private void creditFee(Address proposer, BigInteger fee) {
        if (proposer == null || fee.signum() <= 0) return;

        if (!worldState.accountExists(proposer)) {
            worldState.createEOA(proposer, 0, BigInteger.ZERO);
        }
        worldState.addBalance(proposer, fee);
    }
}
