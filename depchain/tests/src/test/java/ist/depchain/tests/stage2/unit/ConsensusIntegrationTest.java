package ist.depchain.tests.stage2.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;

import ist.depchain.common.ClientRequest;
import ist.depchain.common.ClientRequestMeta;
import ist.depchain.common.Transaction;
import ist.depchain.common.TransactionPayload;
import ist.depchain.core.BlockChain;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.BlockBuilder;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.hotstuff.CommandMempool;
import ist.depchain.tests.stage2.Stage2GasConstants;

/**
 * Unit tests for Consensus Integration.
 * Tests mempool batching, multi-tx block building through the protobuf
 * pipeline, deterministic execution, receipt mapping, and blockchain
 * persistence.
 * No network, keys, BLS, or running consensus required.
 */
class ConsensusIntegrationTest {

    private static final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address CAROL = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");
    private static final Address PROPOSER = Address.fromHexString("0xdddddddddddddddddddddddddddddddddddddd");

    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);

    private DepChainWorldState ws;
    private TransactionExecutor executor;

    @BeforeEach
    void setup() {
        ws = new DepChainWorldState(null);
        executor = new TransactionExecutor();
    }

    // ========== CommandMempool.drainBatch tests ==========

    @Test
    void drainBatchReturnsUpToMaxSize() {
        CommandMempool mempool = new CommandMempool();
        for (int i = 0; i < 5; i++) {
            mempool.enqueue(makeClientRequest("client1", i));
        }

        List<ClientRequest> batch = mempool.drainBatch(3);
        assertEquals(3, batch.size());
        // Remaining 2 should still be in mempool
        assertFalse(mempool.isEmpty());
    }

    @Test
    void drainBatchReturnsAllWhenLessThanMax() {
        CommandMempool mempool = new CommandMempool();
        mempool.enqueue(makeClientRequest("client1", 0));
        mempool.enqueue(makeClientRequest("client1", 1));

        List<ClientRequest> batch = mempool.drainBatch(10);
        assertEquals(2, batch.size());
        assertTrue(mempool.isEmpty());
    }

    @Test
    void drainBatchReturnsEmptyListWhenMempoolEmpty() {
        CommandMempool mempool = new CommandMempool();
        List<ClientRequest> batch = mempool.drainBatch(5);
        assertTrue(batch.isEmpty());
    }

    @Test
    void drainBatchPreservesFIFOOrder() {
        CommandMempool mempool = new CommandMempool();
        mempool.enqueue(makeClientRequest("c1", 10));
        mempool.enqueue(makeClientRequest("c2", 20));
        mempool.enqueue(makeClientRequest("c3", 30));

        List<ClientRequest> batch = mempool.drainBatch(3);
        assertEquals("c1", batch.get(0).getClientId());
        assertEquals(10, batch.get(0).getRequestId());
        assertEquals("c2", batch.get(1).getClientId());
        assertEquals("c3", batch.get(2).getClientId());
    }

    @Test
    void drainBatchRemovesDedupKeys() {
        CommandMempool mempool = new CommandMempool();
        mempool.enqueue(makeClientRequest("c1", 1));
        mempool.drainBatch(1);

        // Re-enqueue the same key - should succeed since drainBatch removed the dedup
        // key
        mempool.enqueue(makeClientRequest("c1", 1));
        assertFalse(mempool.isEmpty());
    }

    @Test
    void drainBatchDeduplicatesDuplicateReplayRequests() {
        CommandMempool mempool = new CommandMempool();
        mempool.enqueue(makeClientRequest("c1", 7));
        mempool.enqueue(makeClientRequest("c1", 7));
        mempool.enqueue(makeClientRequest("c1", 7));

        List<ClientRequest> batch = mempool.drainBatch(10);

        assertEquals(1, batch.size());
        assertTrue(mempool.isEmpty());
    }

    // ========== Protobuf round-trip: Transaction <-> TransactionPayload ==========

    @Test
    void transactionProtoRoundTrip() {
        Transaction original = tx(ALICE, BOB, 1000, 2, 21_000, 0);
        TransactionPayload proto = original.toProto();
        Transaction restored = Transaction.fromProto(proto);

        assertEquals(original.getFrom(), restored.getFrom());
        assertEquals(original.getTo(), restored.getTo());
        assertEquals(original.getValue(), restored.getValue());
        assertEquals(original.getGasPrice(), restored.getGasPrice());
        assertEquals(original.getGasLimit(), restored.getGasLimit());
        assertEquals(original.getNonce(), restored.getNonce());
        assertArrayEquals(original.txHash(), restored.txHash());
    }

    @Test
    void contractDeploymentProtoRoundTrip() {
        Transaction deploy = new Transaction(
                ALICE, null, BigInteger.ZERO,
                new byte[] { 0x60, 0x60, 0x60, 0x40 }, // dummy bytecode
                BigInteger.ONE, BigInteger.valueOf(100_000), 0, null);
        TransactionPayload proto = deploy.toProto();
        Transaction restored = Transaction.fromProto(proto);

        assertNull(restored.getTo());
        assertTrue(restored.isContractDeployment());
        assertArrayEquals(deploy.getData(), restored.getData());
    }

    // ========== End-to-end block execution pipeline ==========

    @Test
    void fullPipelineSingleTransaction() {
        // Setup: Alice has balance
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));

        Transaction tx = tx(ALICE, BOB, 500, 1, 21_000, 0);
        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);

        // Build block
        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        assertEquals(1, block.getTransactions().size());
        assertEquals(1, block.getBlockNumber());

        // Execute
        List<TransactionReceipt> receipts = new ArrayList<>();
        for (Transaction t : block.getTransactions()) {
            receipts.add(executor.execute(ws, t, PROPOSER));
        }

        assertEquals(1, receipts.size());
        assertTrue(receipts.get(0).isSuccess());

        // Verify world state
        assertEquals(BigInteger.valueOf(500), ws.getBalance(BOB));
    }

    @Test
    void fullPipelineMultipleTransactionsDeterministicOrdering() {
        // Setup: accounts with enough balance
        ws.createEOA(ALICE, 0, BigInteger.valueOf(10_000_000));
        ws.createEOA(BOB, 0, BigInteger.valueOf(10_000_000));

        // Different fees to test ordering
        Transaction txLow = tx(ALICE, CAROL, 100, 1, 21_000, 0); // fee = 21_000
        Transaction txHigh = tx(BOB, CAROL, 200, 10, 21_000, 0); // fee = 210_000

        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(List.of(txLow, txHigh), genesis, PROPOSER);

        // High fee should be first
        assertEquals(BigInteger.valueOf(210_000), block.getTransactions().get(0).getMaxFee());
        assertEquals(BigInteger.valueOf(21_000), block.getTransactions().get(1).getMaxFee());

        // Execute in order
        List<TransactionReceipt> receipts = new ArrayList<>();
        for (Transaction t : block.getTransactions()) {
            receipts.add(executor.execute(ws, t, PROPOSER));
        }

        assertTrue(receipts.get(0).isSuccess());
        assertTrue(receipts.get(1).isSuccess());

        // Carol received both transfers
        assertEquals(BigInteger.valueOf(300), ws.getBalance(CAROL));
    }

    @Test
    void fullPipelineWithBlockFinalization() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));

        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);

        List<TransactionReceipt> receipts = new ArrayList<>();
        for (Transaction t : block.getTransactions()) {
            receipts.add(executor.execute(ws, t, PROPOSER));
        }

        BlockChainBlock finalized = BlockBuilder.finalize(block, receipts, "");
        assertEquals(1, finalized.getReceipts().size());
        assertTrue(finalized.getReceipts().get(0).isSuccess());
        assertEquals(block.getBlockHash(), finalized.getBlockHash());
    }

    @Test
    void blockPersistenceAfterExecution() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));

        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChain chain = new BlockChain(); // no persistence dir
        chain.addBlock(genesis);

        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        List<TransactionReceipt> receipts = List.of(executor.execute(ws, tx, PROPOSER));
        BlockChainBlock finalized = BlockBuilder.finalize(block, receipts, "");
        chain.addBlock(finalized);

        assertEquals(2, chain.getHeight());
        assertEquals(finalized.getBlockHash(), chain.getLatestBlock().getBlockHash());
    }

    // ========== Receipt-to-metadata mapping after reordering ==========

    @Test
    void receiptMappingByTxHashAfterReorder() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(10_000_000));
        ws.createEOA(BOB, 0, BigInteger.valueOf(10_000_000));

        // Create txs in "wrong" order (low fee first) - BlockBuilder will reorder
        Transaction txFromAlice = tx(ALICE, CAROL, 100, 1, 21_000, 0); // fee = 21_000
        Transaction txFromBob = tx(BOB, CAROL, 200, 5, 21_000, 0); // fee = 105_000

        // Original order as submitted by clients (matches request_meta order)
        List<Transaction> originalOrder = List.of(txFromAlice, txFromBob);
        List<ClientRequestMeta> metaList = List.of(
                ClientRequestMeta.newBuilder().setClientId("clientA").setRequestId(1).build(),
                ClientRequestMeta.newBuilder().setClientId("clientB").setRequestId(2).build());

        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(originalOrder, genesis, PROPOSER);

        // Execute in BlockBuilder's deterministic order
        List<TransactionReceipt> receipts = new ArrayList<>();
        List<Transaction> orderedTxs = block.getTransactions();
        for (Transaction t : orderedTxs) {
            receipts.add(executor.execute(ws, t, PROPOSER));
        }

        // Build receipt map by tx hash (same logic as executeStage2Block)
        Map<String, TransactionReceipt> receiptByTxHash = new HashMap<>();
        for (int i = 0; i < orderedTxs.size(); i++) {
            String txHashHex = Numeric.toHexStringNoPrefix(orderedTxs.get(i).txHash());
            receiptByTxHash.put(txHashHex, receipts.get(i));
        }

        // Verify each client gets the correct receipt
        for (int i = 0; i < metaList.size(); i++) {
            Transaction originalTx = originalOrder.get(i);
            String txHashHex = Numeric.toHexStringNoPrefix(originalTx.txHash());
            TransactionReceipt receipt = receiptByTxHash.get(txHashHex);

            assertNotNull(receipt, "Receipt should exist for tx from " + metaList.get(i).getClientId());
            assertTrue(receipt.isSuccess());
        }
    }

    // ========== Edge cases ==========

    @Test
    void emptyBlockBuildsSuccessfully() {
        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(List.of(), genesis, PROPOSER);

        assertEquals(0, block.getTransactions().size());
        assertEquals(1, block.getBlockNumber());
        assertNotNull(block.getBlockHash());
    }

    @Test
    void failedTransactionStillIncludedInBlock() {
        // Alice has insufficient balance for the transfer
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100)); // only 100, tx needs 21_100

        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0); // upfront = 100 + 21_000 = 21_100
        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);

        TransactionReceipt receipt = executor.execute(ws, block.getTransactions().get(0), PROPOSER);
        assertFalse(receipt.isSuccess());

        // Block still has the tx
        BlockChainBlock finalized = BlockBuilder.finalize(block, List.of(receipt), "");
        assertEquals(1, finalized.getTransactions().size());
        assertEquals(1, finalized.getReceipts().size());
        assertFalse(finalized.getReceipts().get(0).isSuccess());
    }

    @Test
    void proposerReceivesGasFees() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));

        Transaction tx = tx(ALICE, BOB, 100, 2, 21_000, 0); // fee = min(2*20000, 2*21000) = 40000
        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);

        TransactionReceipt receipt = executor.execute(ws, block.getTransactions().get(0), PROPOSER);
        assertTrue(receipt.isSuccess());

        // Proposer should have received the gas fee
        assertTrue(ws.getBalance(PROPOSER).compareTo(BigInteger.ZERO) > 0);
        assertEquals(BigInteger.valueOf(40_000), ws.getBalance(PROPOSER));
    }

    @Test
    void consecutiveBlocksBuildCorrectChain() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(10_000_000));

        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChain chain = new BlockChain();
        chain.addBlock(genesis);

        // Block 1
        Transaction tx1 = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock block1 = BlockBuilder.build(List.of(tx1), genesis, PROPOSER);
        TransactionReceipt r1 = executor.execute(ws, block1.getTransactions().get(0), PROPOSER);
        BlockChainBlock finalized1 = BlockBuilder.finalize(block1, List.of(r1), "");
        chain.addBlock(finalized1);

        // Block 2
        Transaction tx2 = tx(ALICE, CAROL, 200, 1, 21_000, 1);
        BlockChainBlock block2 = BlockBuilder.build(List.of(tx2), finalized1, PROPOSER);
        TransactionReceipt r2 = executor.execute(ws, block2.getTransactions().get(0), PROPOSER);
        BlockChainBlock finalized2 = BlockBuilder.finalize(block2, List.of(r2), "");
        chain.addBlock(finalized2);

        assertEquals(3, chain.getHeight()); // genesis + 2 blocks
        assertEquals(finalized1.getBlockHash(), finalized2.getPreviousBlockHash());
        assertEquals(2, finalized2.getBlockNumber());
    }

    @Test
    void multipleTransactionsGasFeesAllGoToProposer() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(10_000_000));
        ws.createEOA(BOB, 0, BigInteger.valueOf(10_000_000));

        Transaction tx1 = tx(ALICE, CAROL, 100, 1, 21_000, 0); // fee = 20_000
        Transaction tx2 = tx(BOB, CAROL, 200, 3, 21_000, 0); // fee = 60_000

        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx1, tx2), genesis, PROPOSER);

        for (Transaction t : block.getTransactions()) {
            TransactionReceipt r = executor.execute(ws, t, PROPOSER);
            assertTrue(r.isSuccess());
        }

        // Proposer should get total fees: 20_000 + 60_000 = 80_000
        assertEquals(BigInteger.valueOf(80_000), ws.getBalance(PROPOSER));
    }

    @Test
    void transactionsFailWhenBalanceExhaustedMidBlock() {
        // Alice has enough for the first two txs but not the third.
        // Fee ordering guarantees: tx0 (fee=60_000) -> tx1 (fee=40_000) -> tx2
        // (fee=20_000)
        ws.createEOA(ALICE, 0, BigInteger.valueOf(150_000));

        Transaction tx0 = tx(ALICE, BOB, 10_000, 3, 21_000, 0); // upfront = 10_000 + 63_000 = 73_000
        Transaction tx1 = tx(ALICE, BOB, 10_000, 2, 21_000, 1); // upfront = 10_000 + 42_000 = 52_000
        Transaction tx2 = tx(ALICE, BOB, 10_000, 1, 21_000, 2); // upfront = 10_000 + 31_000 = 31_000

        BlockChainBlock genesis = new BlockChainBlock("genesis", null, List.of(), 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx0, tx1, tx2), genesis, PROPOSER);

        // Verify fee ordering
        List<Transaction> ordered = block.getTransactions();
        assertEquals(BigInteger.valueOf(63_000), ordered.get(0).getMaxFee());
        assertEquals(BigInteger.valueOf(42_000), ordered.get(1).getMaxFee());
        assertEquals(BigInteger.valueOf(21_000), ordered.get(2).getMaxFee());

        List<TransactionReceipt> receipts = new ArrayList<>();
        for (Transaction t : ordered) {
            receipts.add(executor.execute(ws, t, PROPOSER));
        }

        // After tx0: 150_000 - 73_000 + 3_000 refund = 80_000 remaining
        // After tx1: 80_000 - 52_000 + 2_000 refund = 30_000 remaining
        // tx2 needs 31_000 > 30_000 -> fails
        assertTrue(receipts.get(0).isSuccess());
        assertTrue(receipts.get(1).isSuccess());
        assertFalse(receipts.get(2).isSuccess());
        assertEquals("insufficient balance for upfront cost", receipts.get(2).getError());

        // BOB received only the first two transfers
        assertEquals(Stage2GasConstants.NATIVE_TRANSFER_GAS_COST, ws.getBalance(BOB));
        // Alice's balance unchanged by the failed tx
        assertEquals(BigInteger.valueOf(30_000), ws.getBalance(ALICE));
    }

    // ========== Helpers ==========

    private static Transaction tx(Address from, Address to, long value,
            long gasPrice, long gasLimit, long nonce) {
        return new Transaction(from, to, BigInteger.valueOf(value), new byte[0],
                BigInteger.valueOf(gasPrice), BigInteger.valueOf(gasLimit), nonce, null);
    }

    private static ClientRequest makeClientRequest(String clientId, int requestId) {
        TransactionPayload txPayload = new Transaction(
                ALICE, BOB, BigInteger.valueOf(100), new byte[0],
                GAS_PRICE, GAS_LIMIT, requestId, null).toProto();

        return ClientRequest.newBuilder()
                .setClientId(clientId)
                .setRequestId(requestId)
                .setTransaction(txPayload)
                .build();
    }
}
