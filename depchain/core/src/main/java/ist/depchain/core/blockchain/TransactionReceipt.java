package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;

/**
 * Result of executing a single transaction against the world state.
 */
public class TransactionReceipt {
    private final byte[] txHash;
    private final boolean success;
    private final BigInteger gasUsed;
    private final BigInteger fee; // actual fee charged = min(gasPrice*gasLimit, gasPrice*gasUsed)
    private final String error; // null if success
    private final byte[] returnData; // EVM return data (empty for native transfers)
    private final Address contractAddress; // non-null only for successful deployments

    public TransactionReceipt(byte[] txHash, boolean success, BigInteger gasUsed, BigInteger fee,
                              String error, byte[] returnData, Address contractAddress) {
        this.txHash = txHash;
        this.success = success;
        this.gasUsed = gasUsed;
        this.fee = fee;
        this.error = error;
        this.returnData = returnData != null ? returnData : new byte[0];
        this.contractAddress = contractAddress;
    }

    public static TransactionReceipt success(byte[] txHash, BigInteger gasUsed, BigInteger fee) {
        return new TransactionReceipt(txHash, true, gasUsed, fee, null, new byte[0], null);
    }

    public static TransactionReceipt success(byte[] txHash, BigInteger gasUsed, BigInteger fee,
                                             byte[] returnData, Address contractAddress) {
        return new TransactionReceipt(txHash, true, gasUsed, fee, null, returnData, contractAddress);
    }

    public static TransactionReceipt failure(byte[] txHash, BigInteger gasUsed, BigInteger fee, String error) {
        return new TransactionReceipt(txHash, false, gasUsed, fee, error, new byte[0], null);
    }

    // Getters
    public byte[] getTxHash() { return txHash; }
    public boolean isSuccess() { return success; }
    public BigInteger getGasUsed() { return gasUsed; }
    public BigInteger getFee() { return fee; }
    public String getError() { return error; }
    public byte[] getReturnData() { return returnData; }
    public Address getContractAddress() { return contractAddress; }
}
