package ist.depchain.core.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;

public class Transaction {
    private final Address from;
    private final Address to; // null for contract deployment
    private final BigInteger value; // DepCoin transfer amount (in Wei)
    private final byte[] data; // calldata or deployment bytecode
    private final BigInteger gasPrice;
    private final BigInteger gasLimit;
    private final long nonce;
    private final byte[] signature; // ECDSA signature over tx fields

    public Transaction(Address from, Address to, BigInteger value, byte[] data,
                       BigInteger gasPrice, BigInteger gasLimit, long nonce, byte[] signature) {
        this.from = from;
        this.to = to;
        this.value = value != null ? value : BigInteger.ZERO;
        this.data = data != null ? data : new byte[0];
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.nonce = nonce;
        this.signature = signature;
    }

    public boolean isContractDeployment() {
        return to == null;
    }

    public boolean isContractCall() {
        return to != null && data.length > 0;
    }

    public boolean isNativeTransfer() {
        return to != null && data.length == 0 && value.compareTo(BigInteger.ZERO) > 0;
    }

    public BigInteger getTransactionFee(BigInteger gasUsed) {
        BigInteger feeByLimit = gasPrice.multiply(gasLimit);
        BigInteger feeByUsed = gasPrice.multiply(gasUsed);
        return feeByLimit.compareTo(feeByUsed) < 0 ? feeByLimit : feeByUsed;
    }

    public BigInteger getMaxFee() {
        return gasPrice.multiply(gasLimit);
    }

    // Getters
    public Address getFrom() { return from; }
    public Address getTo() { return to; }
    public BigInteger getValue() { return value; }
    public byte[] getData() { return data; }
    public BigInteger getGasPrice() { return gasPrice; }
    public BigInteger getGasLimit() { return gasLimit; }
    public long getNonce() { return nonce; }
    public byte[] getSignature() { return signature; }
}
