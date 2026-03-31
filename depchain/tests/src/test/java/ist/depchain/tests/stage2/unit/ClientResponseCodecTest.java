package ist.depchain.tests.stage2.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import ist.depchain.common.ClientResponse;
import ist.depchain.common.utils.ClientResponseHelper;

/**
 * Unit tests for ClientResponseHelper, which handles encoding/decoding of client response data,
 * especially for native balance snapshots and formatting committed responses.
 */
class ClientResponseHelperTest {

    @Test
    void nativeBalanceSnapshotRoundTrips() {
        BigInteger balance = BigInteger.valueOf(123_456_789);
        String stateHash = "0x" + "ab".repeat(32);

        byte[] encoded = ClientResponseHelper.encodeNativeBalanceSnapshot(balance, stateHash);
        assertEquals(64, encoded.length);

        ClientResponseHelper.NativeBalanceSnapshot decoded = ClientResponseHelper.decodeNativeBalanceSnapshot(encoded);
        assertEquals(balance, decoded.getBalance());
        assertEquals(stateHash, decoded.getStateHash());
    }

    @Test
    void committedResponseFormattingDecodesNativeAndTokenBalances() {
        String stateHash = "0x" + "11".repeat(32);
        byte[] nativePayload = ClientResponseHelper.encodeNativeBalanceSnapshot(BigInteger.valueOf(999), stateHash);
        byte[] tokenPayload = BigInteger.valueOf(777).toByteArray();

        ClientResponse nativeResponse = baseResponse(nativePayload);
        ClientResponse tokenResponse = baseResponse(tokenPayload);

        String nativeFormatted = ClientResponseHelper.formatCommittedResponse(
                "native.balanceOf(0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)", nativeResponse);
        String tokenFormatted = ClientResponseHelper.formatCommittedResponse(
                "erc20.balanceOf(0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)", tokenResponse);

        assertTrue(nativeFormatted.contains("Native balance: 999 DepCoin"));
        assertTrue(nativeFormatted.contains(stateHash));
        assertTrue(tokenFormatted.contains("ERC20 balance: 777 IST"));
    }

    @Test
    void canonicalResponseIdDependsOnReturnData() {
        ClientResponse responseA = baseResponse(new byte[] { 0x01 });
        ClientResponse responseB = baseResponse(new byte[] { 0x02 });

        String idA = ClientResponseHelper.canonicalResponseId(responseA);
        String idB = ClientResponseHelper.canonicalResponseId(responseB);

        assertNotEquals(idA, idB);
    }

    @Test
    void malformedNativeBalanceSnapshotIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientResponseHelper.decodeNativeBalanceSnapshot(new byte[10]));
    }

    private static ClientResponse baseResponse(byte[] returnData) {
        return ClientResponse.newBuilder()
                .setClientId("client1")
                .setRequestId(1)
                .setCommitted(true)
                .setBlockId(ByteString.copyFromUtf8("block-1"))
                .setTxHash(ByteString.copyFrom(new byte[32]))
                .setStatus("COMMITTED_SUCCESS")
                .setError("")
                .setReturnData(ByteString.copyFrom(returnData))
                .build();
    }
}