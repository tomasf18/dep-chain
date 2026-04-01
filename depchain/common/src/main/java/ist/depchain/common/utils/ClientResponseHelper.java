package ist.depchain.common.utils;

import com.google.protobuf.ByteString;

import java.math.BigInteger;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.tuweni.bytes.Bytes;
import org.web3j.utils.Numeric;

import ist.depchain.common.ClientResponse;

public final class ClientResponseHelper {
    private static final String NATIVE_BALANCE_PREFIX = "native.balanceOf(";
    private static final String TOKEN_BALANCE_PREFIX = "erc20.balanceOf(";

    private ClientResponseHelper() {
    }

    public static boolean isNativeBalanceQuery(String requestDescription) {
        return requestDescription != null && requestDescription.startsWith(NATIVE_BALANCE_PREFIX);
    }

    public static boolean isTokenBalanceQuery(String requestDescription) {
        return requestDescription != null && requestDescription.startsWith(TOKEN_BALANCE_PREFIX);
    }

    public static byte[] encodeNativeBalanceSnapshot(BigInteger balance, String stateHashHex) {
        byte[] balanceBytes = toFixedWidthUnsignedBytes(balance, 32);
        byte[] stateHashBytes = hexToBytes(normalizeHex(stateHashHex));

        if (stateHashBytes.length != 32) {
            throw new IllegalArgumentException("state hash must be 32 bytes");
        }

        byte[] out = new byte[64];
        System.arraycopy(balanceBytes, 0, out, 0, 32);
        System.arraycopy(stateHashBytes, 0, out, 32, 32);
        return out;
    }

    public static NativeBalanceSnapshot decodeNativeBalanceSnapshot(byte[] returnData) {
        if (returnData == null || returnData.length < 64) {
            throw new IllegalArgumentException("native balance response must contain 64 bytes: balance + state hash");
        }

        byte[] balanceBytes = Arrays.copyOfRange(returnData, 0, 32);
        byte[] stateHashBytes = Arrays.copyOfRange(returnData, 32, 64);

        return new NativeBalanceSnapshot(new BigInteger(1, balanceBytes), Numeric.toHexString(stateHashBytes));
    }

    public static String formatCommittedResponse(String requestDescription, ClientResponse response) {
        if (response == null) {
            return "";
        }

        if (!response.getError().isBlank()) {
            return "[RESULT] " + response.getError();
        }

        if (isNativeBalanceQuery(requestDescription)) {
            NativeBalanceSnapshot snapshot = decodeNativeBalanceSnapshot(response.getReturnData().toByteArray());
            return "[RESULT] Native balance: " + snapshot.getBalance() + " DepCoin (stateHash="
                    + snapshot.getStateHash() + ", block=" + response.getBlockId().toStringUtf8() + ")";
        }

        if (isTokenBalanceQuery(requestDescription)) {
            BigInteger balance = decodeBigEndianUnsigned(response.getReturnData());
            return "[RESULT] ERC20 balance: " + balance + " IST";
        }

        if (!response.getReturnData().isEmpty()) {
            return "[RESULT] returnData=0x" + Numeric.toHexStringNoPrefix(response.getReturnData().toByteArray());
        }

        return "[RESULT] Operation committed successfully";
    }

    public static String canonicalResponseId(ClientResponse response) {
        return sha256Hex(response.toByteArray());
    }

    private static BigInteger decodeBigEndianUnsigned(ByteString bytes) {
        if (bytes == null || bytes.isEmpty()) {
            return BigInteger.ZERO;
        }
        return new BigInteger(1, bytes.toByteArray());
    }

    private static byte[] toFixedWidthUnsignedBytes(BigInteger value, int widthBytes) {
        if (value == null) {
            value = BigInteger.ZERO;
        }
        byte[] raw = value.signum() == 0 ? new byte[0] : value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        if (raw.length > widthBytes) {
            throw new IllegalArgumentException("value does not fit in " + widthBytes + " bytes");
        }
        byte[] out = new byte[widthBytes];
        System.arraycopy(raw, 0, out, widthBytes - raw.length, raw.length);
        return out;
    }

    private static String normalizeHex(String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("hex value cannot be blank");
        }
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex : "0x" + hex;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Numeric.toHexStringNoPrefix(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        return Bytes.fromHexString(hex).toArrayUnsafe();
    }

    public static final class NativeBalanceSnapshot {
        private final BigInteger balance;
        private final String stateHash;

        private NativeBalanceSnapshot(BigInteger balance, String stateHash) {
            this.balance = balance;
            this.stateHash = stateHash;
        }

        public BigInteger getBalance() {
            return balance;
        }

        public String getStateHash() {
            return stateHash;
        }
    }
}