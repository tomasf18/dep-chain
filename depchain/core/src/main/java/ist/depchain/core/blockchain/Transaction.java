package ist.depchain.core.blockchain;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.hyperledger.besu.datatypes.Address;

public class Transaction {
    private final Address from;
    private final Address to; // null for contract deployment
    private final BigInteger value; // native DepCoin amount in smallest unit
    private final byte[] data; // calldata or deployment bytecode
    private final BigInteger gasPrice;
    private final BigInteger gasLimit;
    private final long nonce;
    private final byte[] signature; // ECDSA signature over unsigned tx bytes

    public Transaction(Address from, Address to, BigInteger value, byte[] data,
                       BigInteger gasPrice, BigInteger gasLimit, long nonce, byte[] signature) {
        this.from = from;
        this.to = to;
        this.value = value != null ? value : BigInteger.ZERO;
        this.data = data != null ? Arrays.copyOf(data, data.length) : new byte[0];
        this.gasPrice = gasPrice != null ? gasPrice : BigInteger.ZERO;
        this.gasLimit = gasLimit != null ? gasLimit : BigInteger.ZERO;
        this.nonce = nonce;
        this.signature = signature != null ? Arrays.copyOf(signature, signature.length) : null;
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

    /**
     * Maximum amount the sender may pay for gas.
     * Actual charged fee is determined later during execution.
     */
    public BigInteger getMaxFee() {
        return gasPrice.multiply(gasLimit);
    }

    /**
     * Canonical unsigned serialization to be used for signing, verification,
     * transaction hashing, tie-breaking, and receipts.
     */
    public byte[] toUnsignedBytes() {
        byte[] fromBytes = from.toArrayUnsafe();
        byte[] toBytes = to == null ? new byte[0] : to.toArrayUnsafe();
        byte[] valueBytes = value.toByteArray();
        byte[] gasPriceBytes = gasPrice.toByteArray();
        byte[] gasLimitBytes = gasLimit.toByteArray();
        byte[] nonceBytes = ByteBuffer.allocate(Long.BYTES).putLong(nonce).array();

        byte[] deploymentFlag = (to == null ? "DEPLOY" : "CALL").getBytes(StandardCharsets.UTF_8);

        int totalSize =
                4 + fromBytes.length +
                4 + toBytes.length +
                4 + valueBytes.length +
                4 + gasPriceBytes.length +
                4 + gasLimitBytes.length +
                4 + nonceBytes.length +
                4 + data.length +
                4 + deploymentFlag.length;

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        putWithLength(buffer, fromBytes);
        putWithLength(buffer, toBytes);
        putWithLength(buffer, valueBytes);
        putWithLength(buffer, gasPriceBytes);
        putWithLength(buffer, gasLimitBytes);
        putWithLength(buffer, nonceBytes);
        putWithLength(buffer, data);
        putWithLength(buffer, deploymentFlag);

        return buffer.array();
    }

    private static void putWithLength(ByteBuffer buffer, byte[] bytes) {
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    // Getters
    public Address getFrom() { return from; }
    public Address getTo() { return to; }
    public BigInteger getValue() { return value; }
    public byte[] getData() { return data; }
    public BigInteger getGasPrice() { return gasPrice; }
    public BigInteger getGasLimit() { return gasLimit; }
    public long getNonce() { return nonce; }
    public byte[] getSignature() {
        return signature == null ? null : Arrays.copyOf(signature, signature.length);
    }
}
