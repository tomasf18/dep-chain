package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

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

        BigInteger fee = feeByLimit.min(feeByUsed);
        creditFee(state, proposer, fee);

        BigInteger refund = feeByLimit.subtract(fee);
        if (refund.signum() > 0) { // refund unused gas
            state.addBalance(tx.getFrom(), refund);
        }

        return TransactionReceipt.success(txHash, gasUsed, fee);
    }

    private TransactionReceipt executeContractDeployment(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {
        BigInteger gasUsed = tx.getGasLimit();
        BigInteger fee = tx.getMaxFee();

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

        state.registerExistingContractAccount(contractAddress);
        return TransactionReceipt.success(txHash, gasUsed, fee, result.getReturnData().toArrayUnsafe(), contractAddress);
    }

    private TransactionReceipt executeContractCall(DepChainWorldState state, Transaction tx, byte[] txHash, Address proposer) {
        BigInteger gasUsed = tx.getGasLimit();
        BigInteger fee = tx.getMaxFee();

        if (tx.getValue().signum() > 0) {
            creditFee(state, proposer, fee);
            state.addBalance(tx.getFrom(), tx.getValue()); // refund value part
            return TransactionReceipt.failure(txHash, gasUsed, fee, "contract call with non-zero native value not supported");
        }

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

        trackKnownErc20MutationSlots(state, tx);
        return TransactionReceipt.success(txHash, gasUsed, fee, result.getReturnData().toArrayUnsafe(), null);
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

    private void trackKnownErc20MutationSlots(DepChainWorldState state, Transaction tx) {
        Address contractAddress = tx.getTo();
        if (contractAddress == null) return;

        String calldataHex = Bytes.wrap(tx.getData()).toHexString().substring(2);
        if (calldataHex.length() < 8) return;

        String selector = calldataHex.substring(0, 8);

        // transfer(address,uint256)
        if (selector.equalsIgnoreCase(EvmService.selector("transfer(address,uint256)"))) {
            Address sender = tx.getFrom();
            Address recipient = decodeAddressArg(calldataHex, 0);

            refreshErc20BalanceSlot(state, contractAddress, sender);
            refreshErc20BalanceSlot(state, contractAddress, recipient);
            return;
        }

        // increaseAllowance(address,uint256)
        if (selector.equalsIgnoreCase(EvmService.selector("increaseAllowance(address,uint256)"))
                || selector.equalsIgnoreCase(EvmService.selector("decreaseAllowance(address,uint256)"))) {
            Address owner = tx.getFrom();
            Address spender = decodeAddressArg(calldataHex, 0);

            UInt256 slot = EvmService.erc20AllowanceSlot(owner, spender);
            state.registerStorageSlot(contractAddress, slot);
            state.refreshTrackedStorageValue(contractAddress, slot);
            return;
        }

        // transferFrom(address,address,uint256)
        if (selector.equalsIgnoreCase(EvmService.selector("transferFrom(address,address,uint256)"))) {
            Address owner = decodeAddressArg(calldataHex, 0);
            Address recipient = decodeAddressArg(calldataHex, 1);
            Address spender = tx.getFrom();

            refreshErc20BalanceSlot(state, contractAddress, owner);
            refreshErc20BalanceSlot(state, contractAddress, recipient);

            UInt256 allowanceSlot = EvmService.erc20AllowanceSlot(owner, spender);
            state.registerStorageSlot(contractAddress, allowanceSlot);
            state.refreshTrackedStorageValue(contractAddress, allowanceSlot);
        }
    }

    private void refreshErc20BalanceSlot(DepChainWorldState state, Address contractAddress, Address owner) {
        UInt256 slot = EvmService.erc20BalanceSlot(owner);
        state.registerStorageSlot(contractAddress, slot);
        state.refreshTrackedStorageValue(contractAddress, slot);
    }

    private Address decodeAddressArg(String calldataHex, int argIndex) {
        int start = 8 + argIndex * 64;
        String word = calldataHex.substring(start, start + 64);
        String addressHex = word.substring(24);
        return Address.fromHexString("0x" + addressHex);
    }
}