package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;
import org.apache.tuweni.bytes.Bytes;

import ist.depchain.common.Transaction;
import ist.depchain.common.utils.ClientResponseHelper;

/**
 * Executes transactions against a provided world state.
 *
 * Currently supports native DepCoin transfers with gas accounting, 
 * EVM-based contract deployments and calls with actual gas accounting from Besu traces.
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
    private static final BigInteger NATIVE_TRANSFER_GAS = BigInteger.valueOf(20000);

    public TransactionExecutor() {
        // Empty constructor 
    }
    

    public TransactionReceipt execute(DepChainWorldState state, Transaction tx, Address proposer) {
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

        if (tx.isNativeBalanceQuery()) {
            return executeNativeBalanceQuery(state, tx, txHash, proposer);
        } else if (tx.isContractDeployment()) {
            return executeContractDeployment(state, tx, txHash, proposer);
        } else if (tx.isContractCall()) {
            return executeContractCall(state, tx, txHash, proposer);
        } else {
            return executeNativeTransfer(state, tx, txHash, proposer);
        }
    }

    private TransactionReceipt executeNativeBalanceQuery(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {
        BigInteger gasUsed = NATIVE_TRANSFER_GAS;
        BigInteger gasLimit = tx.getGasLimit();
        BigInteger gasPrice = tx.getGasPrice();

        BigInteger feeByLimit = gasPrice.multiply(gasLimit);
        BigInteger feeByUsed = gasPrice.multiply(gasUsed);

        if (gasLimit.compareTo(gasUsed) < 0) {
            BigInteger fee = feeByLimit;
            creditFee(state, proposer, fee);
            return TransactionReceipt.failure(txHash, gasUsed, fee, "out of gas: native balance query requires " + NATIVE_TRANSFER_GAS + " gas");
        }

        Address target = tx.getNativeBalanceQueryTarget();
        if (target == null) {
            BigInteger fee = feeByUsed;
            creditFee(state, proposer, fee);
            BigInteger refund = feeByLimit.subtract(fee);
            if (refund.signum() > 0) {
                state.addBalance(tx.getFrom(), refund);
            }
            return TransactionReceipt.failure(txHash, gasUsed, fee, "missing native balance query target");
        }

        BigInteger fee = feeByUsed;
        creditFee(state, proposer, fee);

        BigInteger refund = feeByLimit.subtract(fee);
        if (refund.signum() > 0) {
            state.addBalance(tx.getFrom(), refund);
        }

        BigInteger targetBalance = state.getBalance(target);
        String stateHash = state.computeStateHash();
        byte[] returnData = ClientResponseHelper.encodeNativeBalanceSnapshot(targetBalance, stateHash);

        return TransactionReceipt.success(txHash, gasUsed, fee, returnData, null);
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

        return TransactionReceipt.success(txHash, gasUsed, fee);
    }

    private TransactionReceipt executeContractDeployment(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {
        if (tx.getValue().signum() > 0) { // if the transaction tries to transfer native value while deploying a contract, we simply refund the value and charge the fee
            BigInteger gasUsed = BigInteger.ZERO;
            BigInteger fee = tx.getMaxFee();
            creditFee(state, proposer, fee);
            state.addBalance(tx.getFrom(), tx.getValue()); // refund value part
            return TransactionReceipt.failure(txHash, gasUsed, fee, "contract deployment with non-zero native value not supported");
        }

        Address contractAddress = EvmService.deriveContractAddress(tx.getFrom(), tx.getNonce());
        EvmService.EvmResult result = EvmService.deployContract(state, tx.getFrom(), contractAddress, tx.getData(), tx.getGasLimit());

        BigInteger gasUsed = result.getGasUsed();
        BigInteger fee = gasUsed.multiply(tx.getGasPrice());

        if (!result.isSuccess() && shouldChargeFullGasLimit(result, gasUsed, tx.getGasLimit())) {
            gasUsed = tx.getGasLimit();
            fee = tx.getMaxFee();
        }

        creditFee(state, proposer, fee);
        BigInteger refund = tx.getMaxFee().subtract(fee);
        if (refund.signum() > 0) {
            state.addBalance(tx.getFrom(), refund);
        }

        if (!result.isSuccess()) {
            return TransactionReceipt.failure(txHash, gasUsed, fee, result.getErrorMessage() == null ? "contract deployment failed" : result.getErrorMessage());
        }

        return TransactionReceipt.success(txHash, gasUsed, fee, result.getReturnData().toArrayUnsafe(), contractAddress);
    }

    private TransactionReceipt executeContractCall(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {
        if (tx.getValue().signum() > 0) {
            BigInteger gasUsed = BigInteger.ZERO;
            BigInteger fee = tx.getMaxFee();
            creditFee(state, proposer, fee);
            state.addBalance(tx.getFrom(), tx.getValue()); // refund value part
            return TransactionReceipt.failure(txHash, gasUsed, fee, "contract call with non-zero native value not supported");
        }

        // Checks if the target account exists and has code before attempting the call, to avoid creating new accounts 
        Address contractAddress = tx.getTo();
        if (contractAddress == null) {
            BigInteger gasUsed = BigInteger.ZERO;
            BigInteger fee = tx.getMaxFee();
            creditFee(state, proposer, fee);
            return TransactionReceipt.failure(txHash, gasUsed, fee, "missing contract address");
        }

        if (!state.accountExists(contractAddress)) {
            BigInteger gasUsed = BigInteger.ZERO;
            BigInteger fee = tx.getMaxFee();
            creditFee(state, proposer, fee);
            return TransactionReceipt.failure(txHash, gasUsed, fee, "target contract account does not exist");
        }

        Bytes runtimeCode = state.getCode(contractAddress);
        if (runtimeCode == null || runtimeCode.isEmpty()) {
            BigInteger gasUsed = BigInteger.ZERO;
            BigInteger fee = tx.getMaxFee();
            creditFee(state, proposer, fee);
            return TransactionReceipt.failure(txHash, gasUsed, fee, "target contract has no runtime code");
        }

        EvmService.EvmResult result = EvmService.callContract(state, tx.getFrom(), contractAddress, runtimeCode, tx.getData(), tx.getGasLimit());

        BigInteger gasUsed = result.getGasUsed();
        BigInteger fee = gasUsed.multiply(tx.getGasPrice());

        if (!result.isSuccess() && shouldChargeFullGasLimit(result, gasUsed, tx.getGasLimit())) {
            gasUsed = tx.getGasLimit();
            fee = tx.getMaxFee();
        }

        creditFee(state, proposer, fee);
        BigInteger refund = tx.getMaxFee().subtract(fee);
        if (refund.signum() > 0) {
            state.addBalance(tx.getFrom(), refund);
        }

        if (!result.isSuccess()) {
            return TransactionReceipt.failure(txHash, gasUsed, fee, result.getErrorMessage() == null ? "contract call failed" : result.getErrorMessage());
        }

        return TransactionReceipt.success(txHash, gasUsed, fee, result.getReturnData().toArrayUnsafe(), null);
    }

    private void creditFee(DepChainWorldState state, Address proposer, BigInteger fee) {
        if (proposer == null || fee.signum() <= 0) {
            return;
        }
        if (!state.accountExists(proposer)) {
            state.createEOA(proposer, 0, BigInteger.ZERO);
        }
        state.addBalance(proposer, fee);
    }

    private boolean shouldChargeFullGasLimit(EvmService.EvmResult result, BigInteger gasUsed, BigInteger gasLimit) {
        if (result == null || result.isSuccess()) {
            return false;
        }

        if (gasUsed == null || gasLimit == null) {
            return false;
        }

        String errorMessage = result.getErrorMessage();
        boolean outOfGasByMessage = errorMessage != null && errorMessage.toLowerCase().contains("out of gas");
        boolean outOfGasByUsage = gasUsed.signum() == 0 || gasUsed.compareTo(gasLimit) >= 0;
        return outOfGasByMessage || outOfGasByUsage;
    }
}