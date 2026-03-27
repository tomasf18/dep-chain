package ist.depchain.core.hotstuff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import ist.depchain.common.ClientRequest;
import ist.depchain.common.TransactionPayload;

class CommandMempoolTest {

    @Test
    void selectsHighFeeRequestEvenWhenItIsBehindLowerFeeRequests() {
        CommandMempool mempool = new CommandMempool();

        ClientRequest lowFeeFront = request("client1", 1, 1, 21000, 0);
        ClientRequest lowFeeSecond = request("client1", 2, 1, 21000, 1);
        ClientRequest highFeeTail = request("client1", 3, 3, 21000, 2);

        mempool.enqueue(lowFeeFront);
        mempool.enqueue(lowFeeSecond);
        mempool.enqueue(highFeeTail);

        List<ClientRequest> batch = mempool.peekFeeBatch(10, BigInteger.valueOf(63000));

        assertEquals(1, batch.size());
        assertEquals(3, batch.get(0).getRequestId());

        List<ClientRequest> drained = mempool.drainFeeBatch(10, BigInteger.valueOf(63000));
        assertEquals(1, drained.size());
        assertEquals(3, drained.get(0).getRequestId());

        assertEquals(2, mempool.peekBatch(10).size());
        assertFalse(mempool.isEmpty());
        assertTrue(mempool.peekBatch(10).stream().anyMatch(req -> req.getRequestId() == 1));
        assertTrue(mempool.peekBatch(10).stream().anyMatch(req -> req.getRequestId() == 2));
    }

    private static ClientRequest request(String clientId, int requestId, long gasPrice, long gasLimit, long nonce) {
        Address from = Address.fromHexString("0x172bf398d2a931323199521625f471fb1c28879a");
        Address to = Address.fromHexString("0xfe37d77266b312ca364bd3f9386e1df4d193e9d9");

        TransactionPayload tx = TransactionPayload.newBuilder()
                .setFrom(ByteString.copyFrom(from.toArrayUnsafe()))
                .setTo(ByteString.copyFrom(to.toArrayUnsafe()))
                .setValue(ByteString.copyFrom(new byte[] {0}))
                .setData(ByteString.EMPTY)
                .setGasPrice(ByteString.copyFrom(BigInteger.valueOf(gasPrice).toByteArray()))
                .setGasLimit(ByteString.copyFrom(BigInteger.valueOf(gasLimit).toByteArray()))
                .setNonce(nonce)
                .setSignature(ByteString.EMPTY)
                .build();

        return ClientRequest.newBuilder()
                .setClientId(clientId)
                .setRequestId(requestId)
                .setTransaction(tx)
                .setSignature(ByteString.EMPTY)
                .build();
    }
}