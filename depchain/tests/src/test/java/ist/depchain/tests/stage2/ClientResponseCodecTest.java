package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import ist.depchain.common.ClientResponse;
import ist.depchain.common.utils.ClientResponseCodec;

class ClientResponseCodecTest {

    @Test
    void nativeBalanceSnapshotRoundTrips() {
        BigInteger balance = BigInteger.valueOf(123_456_789);
        String stateHash = "0x" + "ab".repeat(32);

        byte[] encoded = ClientResponseCodec.encodeNativeBalanceSnapshot(balance, stateHash);
        assertEquals(64, encoded.length);

        ClientResponseCodec.NativeBalanceSnapshot decoded = ClientResponseCodec.decodeNativeBalanceSnapshot(encoded);
        assertEquals(balance, decoded.getBalance());
        assertEquals(stateHash, decoded.getStateHash());
    }

    @Test
    void committedResponseFormattingDecodesNativeAndTokenBalances() {
        String stateHash = "0x" + "11".repeat(32);
        byte[] nativePayload = ClientResponseCodec.encodeNativeBalanceSnapshot(BigInteger.valueOf(999), stateHash);
        byte[] tokenPayload = BigInteger.valueOf(777).toByteArray();

        ClientResponse nativeResponse = baseResponse(nativePayload);
        ClientResponse tokenResponse = baseResponse(tokenPayload);

        String nativeFormatted = ClientResponseCodec.formatCommittedResponse(
                "native.balanceOf(0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)", nativeResponse);
        String tokenFormatted = ClientResponseCodec.formatCommittedResponse(
                "erc20.balanceOf(0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa)", tokenResponse);

        assertTrue(nativeFormatted.contains("Native balance: 999 DepCoin"));
        assertTrue(nativeFormatted.contains(stateHash));
        assertTrue(tokenFormatted.contains("ERC20 balance: 777 IST"));
    }

    @Test
    void canonicalResponseIdDependsOnReturnData() {
        ClientResponse responseA = baseResponse(new byte[] {0x01});
        ClientResponse responseB = baseResponse(new byte[] {0x02});

        String idA = ClientResponseCodec.canonicalResponseId(responseA);
        String idB = ClientResponseCodec.canonicalResponseId(responseB);

        assertNotEquals(idA, idB);
    }

    @Test
    void malformedNativeBalanceSnapshotIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientResponseCodec.decodeNativeBalanceSnapshot(new byte[10]));
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