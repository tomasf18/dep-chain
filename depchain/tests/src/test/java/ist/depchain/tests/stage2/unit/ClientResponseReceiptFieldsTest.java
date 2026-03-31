package ist.depchain.tests.stage2.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import ist.depchain.common.ClientResponse;

/**
 * Unit tests for ClientResponse, ensuring that all fields, including extended receipt fields,
 * are correctly preserved during serialization and deserialization, and that optional fields
 * can be empty without causing issues.
 */
public class ClientResponseReceiptFieldsTest {

    @Test
    void clientResponsePreservesExtendedReceiptFields() throws Exception {
        byte[] txHash = hexToBytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        byte[] returnData = hexToBytes("deadbeef");
        byte[] contractAddress = hexToBytes("9999999999999999999999999999999999999999");
        byte[] gasUsed = new BigInteger("100000").toByteArray();
        byte[] fee = new BigInteger("100000").toByteArray();

        ClientResponse original = ClientResponse.newBuilder()
                .setClientId("client1")
                .setRequestId(7)
                .setCommitted(true)
                .setBlockId(ByteString.copyFromUtf8("block-123"))
                .setTxHash(ByteString.copyFrom(txHash))
                .setStatus("COMMITTED_SUCCESS")
                .setError("")
                .setReturnData(ByteString.copyFrom(returnData))
                .setContractAddress(ByteString.copyFrom(contractAddress))
                .setGasUsed(ByteString.copyFrom(gasUsed))
                .setFee(ByteString.copyFrom(fee))
                .build();

        byte[] serialized = original.toByteArray();
        ClientResponse parsed = ClientResponse.parseFrom(serialized);

        assertEquals(original.getClientId(), parsed.getClientId());
        assertEquals(original.getRequestId(), parsed.getRequestId());
        assertEquals(original.getCommitted(), parsed.getCommitted());
        assertEquals(original.getBlockId(), parsed.getBlockId());
        assertEquals(original.getTxHash(), parsed.getTxHash());
        assertEquals(original.getStatus(), parsed.getStatus());
        assertEquals(original.getError(), parsed.getError());
        assertEquals(original.getReturnData(), parsed.getReturnData());
        assertEquals(original.getContractAddress(), parsed.getContractAddress());
        assertEquals(original.getGasUsed(), parsed.getGasUsed());
        assertEquals(original.getFee(), parsed.getFee());
    }

    @Test
    void clientResponseAllowsEmptyOptionalReceiptFields() throws Exception {
        ClientResponse original = ClientResponse.newBuilder()
                .setClientId("client2")
                .setRequestId(9)
                .setCommitted(true)
                .setBlockId(ByteString.copyFromUtf8("block-9"))
                .setTxHash(ByteString.copyFrom(new byte[32]))
                .setStatus("COMMITTED_FAILURE")
                .setError("some revert")
                .build();

        ClientResponse parsed = ClientResponse.parseFrom(original.toByteArray());

        assertTrue(parsed.getReturnData().isEmpty());
        assertTrue(parsed.getContractAddress().isEmpty());
        assertTrue(parsed.getGasUsed().isEmpty());
        assertTrue(parsed.getFee().isEmpty());
        assertEquals("COMMITTED_FAILURE", parsed.getStatus());
        assertEquals("some revert", parsed.getError());
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}