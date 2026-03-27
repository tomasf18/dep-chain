package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;
import org.apache.tuweni.bytes.Bytes;

import ist.depchain.common.Transaction;

/**
 * Executes transactions against a provided world state.
 *
 * Currently supports native DepCoin transfers with gas accounting, 
 * EVM-based contract deployments and calls without gas accounting 
 * (i.e. we charge the max fee regardless of actual gas used, and do not refund unused gas).
 *
 * Gas rules:
 * fee = min(gasPrice * gasLimit, gasPrice * gasUsed)
 * If gasUsed > gasLimit -> tx aborted, gas NOT refunded.
 * If gasUsed < gasLimit -> tx success, refund (gasLimit - gasUsed) * gasPrice to sender.
 * Fees are deducted from sender's native DepCoin balance.
 * Fees are credited to the block proposer (leader).
 */
public class TransactionExecutor {

    /** Fixed gas cost for a native DepCoin transfer (no EVM involved). */
    private static final BigInteger NATIVE_TRANSFER_GAS = BigInteger.valueOf(21000);

    public TransactionExecutor() {
        // Empty constructor 
    }
    

    public TransactionReceipt execute(DepChainWorldState state, Transaction tx, Address proposer, boolean logging) {
        byte[] txHash = tx.txHash();
        Address sender = tx.getFrom();

        BigInteger upfrontCost = tx.getMaxUpfrontCost();
        if (upfrontCost.signum() < 0) {
            return TransactionReceipt.failure(txHash, BigInteger.ZERO, BigInteger.ZERO, "negative upfront cost");
        }

        if (state.getBalance(sender).compareTo(upfrontCost) < 0) {
            return TransactionReceipt.failure(txHash, BigInteger.ZERO, BigInteger.ZERO, "insufficient balance for upfront cost");
        }

        // Reserve max upfront cost immediately.
        state.subtractBalance(sender, upfrontCost);

        // Nonce always advances for an accepted-on-chain transaction, even if execution
        // fails deterministically.
        state.incrementNonce(sender);

        if (tx.isContractDeployment()) {
            return executeContractDeployment(state, tx, txHash, proposer, logging);
        } else if (tx.isContractCall()) {
            return executeContractCall(state, tx, txHash, proposer, logging);
        } else {
            return executeNativeTransfer(state, tx, txHash, proposer, logging);
        }
    }

    private TransactionReceipt executeNativeTransfer(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer, boolean logging) {
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

            // Refund the transferred value, but not gas
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

        // Charge fee based on actual gas used, not the limit. Refund the difference.
        BigInteger fee = feeByLimit.min(feeByUsed);
        creditFee(state, proposer, fee);

        BigInteger refund = feeByLimit.subtract(fee);
        if (refund.signum() > 0) { // refund unused gas
            state.addBalance(tx.getFrom(), refund);
        }

        if (logging) {
            System.out.println(state.printWorldState());
        }

        return TransactionReceipt.success(txHash, gasUsed, fee);
    }

    private TransactionReceipt executeContractDeployment(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer, boolean logging) {
        BigInteger gasUsed = tx.getGasLimit();
        BigInteger fee = tx.getMaxFee(); // charge max fee for simplicity, regardless of actual gas used (max fee is gas_price * gas_limit)

        if (tx.getValue().signum() > 0) { // if the transaction tries to transfer native value while deploying a contract, we simply refund the value and charge the fee
            creditFee(state, proposer, fee);
            state.addBalance(tx.getFrom(), tx.getValue()); // refund value part
            return TransactionReceipt.failure(txHash, gasUsed, fee, "contract deployment with non-zero native value not supported");
        }

        Address contractAddress = EvmService.deriveContractAddress(tx.getFrom(), tx.getNonce());
        EvmService.EvmResult result = EvmService.deployContract(state, tx.getFrom(), contractAddress, tx.getData());

        creditFee(state, proposer, fee);

        if (!result.isSuccess()) {
            return TransactionReceipt.failure(txHash, gasUsed, fee, result.getErrorMessage() == null ? "contract deployment failed" : result.getErrorMessage());
        }

        return TransactionReceipt.success(txHash, gasUsed, fee, result.getReturnData().toArrayUnsafe(), contractAddress);
    }

    private TransactionReceipt executeContractCall(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer, boolean logging) {
        BigInteger gasUsed = tx.getGasLimit();
        BigInteger fee = tx.getMaxFee();

        if (tx.getValue().signum() > 0) {
            creditFee(state, proposer, fee);
            state.addBalance(tx.getFrom(), tx.getValue()); // refund value part
            return TransactionReceipt.failure(txHash, gasUsed, fee, "contract call with non-zero native value not supported");
        }

        // Checks if the target account exists and has code before attempting the call, to avoid creating new accounts 
        Address contractAddress = tx.getTo();
        if (contractAddress == null) {
            creditFee(state, proposer, fee);
            return TransactionReceipt.failure(txHash, gasUsed, fee, "missing contract address");
        }

        if (!state.accountExists(contractAddress)) {
            creditFee(state, proposer, fee);
            return TransactionReceipt.failure(txHash, gasUsed, fee, "target contract account does not exist");
        }

        Bytes runtimeCode = state.getCode(contractAddress);
        if (runtimeCode == null || runtimeCode.isEmpty()) {
            creditFee(state, proposer, fee);
            return TransactionReceipt.failure(txHash, gasUsed, fee, "target contract has no runtime code");
        }

        EvmService.EvmResult result = EvmService.callContract(state, tx.getFrom(), contractAddress, runtimeCode, tx.getData());

        creditFee(state, proposer, fee);

        if (!result.isSuccess()) {
            return TransactionReceipt.failure(txHash, gasUsed, fee, result.getErrorMessage() == null ? "contract call failed" : result.getErrorMessage());
        }

        return TransactionReceipt.success(txHash, gasUsed, fee, result.getReturnData().toArrayUnsafe(), null);
    }

    private void creditFee(DepChainWorldState state, Address proposer, BigInteger fee) {
        String proposerLabel = proposer == null ? "<null>" : proposer.toHexString();
        System.out.println("[DEBUG] Crediting fee of " + fee.toString() + " to proposer " + proposerLabel);
        if (proposer == null || fee.signum() <= 0) {
            return;
        }
        if (!state.accountExists(proposer)) {
            System.out.println("[WARN] Proposer account " + proposer.toHexString() + " does not exist in world state. Creating it to credit fees.");
            state.createEOA(proposer, 0, BigInteger.ZERO);
        }
        state.addBalance(proposer, fee);
    }
}