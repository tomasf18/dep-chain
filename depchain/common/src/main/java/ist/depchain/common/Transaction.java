package ist.depchain.common;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.hyperledger.besu.datatypes.Address;
import com.google.protobuf.ByteString;

import org.apache.tuweni.bytes.Bytes;
import org.web3j.crypto.Hash;

public class Transaction {
    private final Address from;
    private final Address to; // null for contract deployment
    private final BigInteger value; // how much native currency (DepCoin) to transfer; zero for contract calls and deployments
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

    public BigInteger getMaxUpfrontCost() {
        return value.add(getMaxFee());
    }

    public byte[] txHash() {
        return Hash.sha3(toUnsignedBytes());
    }

    public Transaction withSignature(byte[] newSignature) {
        return new Transaction(from, to, value, data, gasPrice, gasLimit, nonce, newSignature);
    }

    /**
     * Canonical unsigned serialization to be used for signing, verification,
     * transaction hashing, tie-breaking, and receipts.
     */
    public byte[] toUnsignedBytes() {
        byte[] fromBytes = from.toArrayUnsafe();
        byte[] toBytes = to == null ? new byte[0] : to.toArrayUnsafe();
        byte[] valueBytes = normalizeBigInt(value);
        byte[] gasPriceBytes = normalizeBigInt(gasPrice);
        byte[] gasLimitBytes = normalizeBigInt(gasLimit);
        byte[] nonceBytes = ByteBuffer.allocate(Long.BYTES).putLong(nonce).array();

        int total =
                4 + fromBytes.length +
                4 + toBytes.length +
                4 + valueBytes.length +
                4 + data.length +
                4 + gasPriceBytes.length +
                4 + gasLimitBytes.length +
                4 + nonceBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(total);
        putWithLength(buf, fromBytes);
        putWithLength(buf, toBytes);
        putWithLength(buf, valueBytes);
        putWithLength(buf, data);
        putWithLength(buf, gasPriceBytes);
        putWithLength(buf, gasLimitBytes);
        putWithLength(buf, nonceBytes);

        return buf.array();
    }
    
    private static void putWithLength(ByteBuffer buffer, byte[] bytes) {
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    public TransactionPayload toProto() {
        return TransactionPayload.newBuilder()
                .setFrom(ByteString.copyFrom(from.toArrayUnsafe()))
                .setTo(ByteString.copyFrom(to == null ? new byte[0] : to.toArrayUnsafe()))
                .setValue(ByteString.copyFrom(normalizeBigInt(value)))
                .setData(ByteString.copyFrom(data))
                .setGasPrice(ByteString.copyFrom(normalizeBigInt(gasPrice)))
                .setGasLimit(ByteString.copyFrom(normalizeBigInt(gasLimit)))
                .setNonce(nonce)
                .setSignature(ByteString.copyFrom(signature == null ? new byte[0] : signature))
                .build();
    }

    public static Transaction fromProto(TransactionPayload proto) {
        Address from = Address.wrap(Bytes.wrap(proto.getFrom().toByteArray()));
        Address to = proto.getTo().isEmpty()
                ? null
                : Address.wrap(Bytes.wrap(proto.getTo().toByteArray()));

        return new Transaction(
                from,
                to,
                fromUnsigned(proto.getValue().toByteArray()),
                proto.getData().toByteArray(),
                fromUnsigned(proto.getGasPrice().toByteArray()),
                fromUnsigned(proto.getGasLimit().toByteArray()),
                proto.getNonce(),
                proto.getSignature().toByteArray()
        );
    }

    private static byte[] normalizeBigInt(BigInteger value) {
        if (value == null || value.equals(BigInteger.ZERO)) {
            return new byte[] {0};
        }
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    private static BigInteger fromUnsigned(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return BigInteger.ZERO;
        }
        return new BigInteger(1, bytes);
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
