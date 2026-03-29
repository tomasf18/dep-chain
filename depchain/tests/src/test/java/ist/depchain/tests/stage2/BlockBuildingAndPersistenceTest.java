package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.web3j.utils.Numeric;

import ist.depchain.common.Transaction;
import ist.depchain.core.BlockChain;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.BlockBuilder;
import ist.depchain.core.blockchain.BlockSerializer;
import ist.depchain.core.blockchain.TransactionReceipt;

/**
 * Unit tests for Step 4: Block structure, deterministic ordering, persistence, and serialization.
 */
class BlockBuildingAndPersistenceTest {

    private static final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address CAROL = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");
    private static final Address PROPOSER = Address.fromHexString("0xdddddddddddddddddddddddddddddddddddddddd");

    private BlockChainBlock genesis;

    @BeforeEach
    void setup() {
        genesis = new BlockChainBlock("0x0000", null, List.of(), 0);
    }

    // --- Deterministic ordering ---

    @Test
    void blockBuilderOrdersByFeeDescending() {
        Transaction lowFee = tx(ALICE, BOB, 100, 1, 21_000, 0);    // fee = 21000
        Transaction highFee = tx(BOB, CAROL, 100, 10, 21_000, 0);  // fee = 210000
        Transaction midFee = tx(CAROL, ALICE, 100, 5, 21_000, 0);  // fee = 105000

        BlockChainBlock block = BlockBuilder.build(List.of(lowFee, highFee, midFee), genesis, PROPOSER);

        List<Transaction> ordered = block.getTransactions();
        assertEquals(3, ordered.size());
        // Highest fee first
        assertEquals(BigInteger.valueOf(210_000), ordered.get(0).getMaxFee());
        assertEquals(BigInteger.valueOf(105_000), ordered.get(1).getMaxFee());
        assertEquals(BigInteger.valueOf(21_000), ordered.get(2).getMaxFee());
    }

    @Test
    void blockBuilderBreaksTiesByTxHash() {
        // Same fee, different from/nonce → different tx hashes → deterministic tie-break
        Transaction tx1 = tx(ALICE, BOB, 100, 1, 21_000, 0);
        Transaction tx2 = tx(BOB, CAROL, 200, 1, 21_000, 0);

        assertEquals(tx1.getMaxFee(), tx2.getMaxFee(), "fees must be equal for this test");

        BlockChainBlock block = BlockBuilder.build(List.of(tx1, tx2), genesis, PROPOSER);
        List<Transaction> ordered = block.getTransactions();

        // Both should be present, in a deterministic order based on tx hash
        assertEquals(2, ordered.size());

        // Verify the order is by tx hash ascending
        String hash0 = Numeric.toHexStringNoPrefix(ordered.get(0).txHash());
        String hash1 = Numeric.toHexStringNoPrefix(ordered.get(1).txHash());
        assertTrue(hash0.compareTo(hash1) <= 0, "tie-breaker should sort by tx hash ascending");
    }

    @Test
    void orderingIsDeterministicRegardlessOfInputOrder() {
        Transaction tx1 = tx(ALICE, BOB, 50, 3, 21_000, 0);
        Transaction tx2 = tx(BOB, CAROL, 50, 3, 21_000, 0);
        Transaction tx3 = tx(CAROL, ALICE, 50, 1, 21_000, 0);

        BlockChainBlock blockA = BlockBuilder.build(List.of(tx1, tx2, tx3), genesis, PROPOSER);
        BlockChainBlock blockB = BlockBuilder.build(List.of(tx3, tx1, tx2), genesis, PROPOSER);
        BlockChainBlock blockC = BlockBuilder.build(List.of(tx2, tx3, tx1), genesis, PROPOSER);

        // All three should produce the same transaction order
        assertTransactionOrderEquals(blockA.getTransactions(), blockB.getTransactions());
        assertTransactionOrderEquals(blockB.getTransactions(), blockC.getTransactions());
    }

    // --- Block hash computation ---

    @Test
    void blockHashIsDeterministic() {
        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock block1 = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        BlockChainBlock block2 = BlockBuilder.build(List.of(tx), genesis, PROPOSER);

        assertEquals(block1.getBlockHash(), block2.getBlockHash());
    }

    @Test
    void differentTransactionsProduceDifferentHashes() {
        Transaction tx1 = tx(ALICE, BOB, 100, 1, 21_000, 0);
        Transaction tx2 = tx(ALICE, BOB, 200, 1, 21_000, 0);

        BlockChainBlock block1 = BlockBuilder.build(List.of(tx1), genesis, PROPOSER);
        BlockChainBlock block2 = BlockBuilder.build(List.of(tx2), genesis, PROPOSER);

        assertNotEquals(block1.getBlockHash(), block2.getBlockHash());
    }

    @Test
    void differentProposersProduceDifferentHashes() {
        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        Address otherProposer = Address.fromHexString("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");

        BlockChainBlock block1 = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        BlockChainBlock block2 = BlockBuilder.build(List.of(tx), genesis, otherProposer);

        assertNotEquals(block1.getBlockHash(), block2.getBlockHash());
    }

    // --- Block metadata ---

    @Test
    void blockNumberIncrementsFromParent() {
        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock block1 = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        assertEquals(1, block1.getBlockNumber());

        BlockChainBlock block2 = BlockBuilder.build(List.of(tx), block1, PROPOSER);
        assertEquals(2, block2.getBlockNumber());
    }

    @Test
    void blockPointsToParentHash() {
        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        assertEquals(genesis.getBlockHash(), block.getPreviousBlockHash());
    }

    @Test
    void proposerIsStoredInBlock() {
        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        assertEquals(PROPOSER, block.getProposer());
    }

    // --- Finalize with receipts ---

    @Test
    void finalizeAttachesReceipts() {
        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx), genesis, PROPOSER);

        assertTrue(block.getReceipts().isEmpty());

        TransactionReceipt receipt = TransactionReceipt.success(
                tx.txHash(), BigInteger.valueOf(21_000), BigInteger.valueOf(21_000));

        BlockChainBlock finalized = BlockBuilder.finalize(block, List.of(receipt), "");
        assertEquals(1, finalized.getReceipts().size());
        assertTrue(finalized.getReceipts().get(0).isSuccess());
        assertEquals(block.getBlockHash(), finalized.getBlockHash());
    }

    // --- JSON serialization round-trip ---

    @Test
    void serializeAndDeserializeBlock() {
        Transaction tx1 = tx(ALICE, BOB, 500, 2, 50_000, 0);
        Transaction tx2 = tx(BOB, CAROL, 300, 1, 21_000, 0);
        BlockChainBlock block = BlockBuilder.build(List.of(tx1, tx2), genesis, PROPOSER);

        TransactionReceipt r1 = TransactionReceipt.success(
                tx1.txHash(), BigInteger.valueOf(21_000), BigInteger.valueOf(42_000));
        TransactionReceipt r2 = TransactionReceipt.failure(
                tx2.txHash(), BigInteger.valueOf(21_000), BigInteger.valueOf(21_000), "some error");
        BlockChainBlock finalized = BlockBuilder.finalize(block, List.of(r1, r2), "");

        String json = BlockSerializer.toJson(finalized);
        BlockChainBlock restored = BlockSerializer.fromJson(json);

        assertEquals(finalized.getBlockHash(), restored.getBlockHash());
        assertEquals(finalized.getPreviousBlockHash(), restored.getPreviousBlockHash());
        assertEquals(finalized.getBlockNumber(), restored.getBlockNumber());
        assertEquals(finalized.getProposer(), restored.getProposer());
        assertEquals(finalized.getTransactions().size(), restored.getTransactions().size());
        assertEquals(finalized.getReceipts().size(), restored.getReceipts().size());

        // Verify transaction fields survived round-trip
        Transaction origTx = finalized.getTransactions().get(0);
        Transaction restTx = restored.getTransactions().get(0);
        assertEquals(origTx.getFrom(), restTx.getFrom());
        assertEquals(origTx.getTo(), restTx.getTo());
        assertEquals(origTx.getValue(), restTx.getValue());
        assertEquals(origTx.getGasPrice(), restTx.getGasPrice());
        assertEquals(origTx.getGasLimit(), restTx.getGasLimit());
        assertEquals(origTx.getNonce(), restTx.getNonce());

        // Verify receipt fields survived round-trip
        assertTrue(restored.getReceipts().get(0).isSuccess());
        assertFalse(restored.getReceipts().get(1).isSuccess());
        assertEquals("some error", restored.getReceipts().get(1).getError());
    }

    @Test
    void serializeGenesisBlockWithNulls() {
        BlockChainBlock genesisBlock = new BlockChainBlock("0x0000", null, List.of(), 0);

        String json = BlockSerializer.toJson(genesisBlock);
        BlockChainBlock restored = BlockSerializer.fromJson(json);

        assertEquals("0x0000", restored.getBlockHash());
        assertNull(restored.getPreviousBlockHash());
        assertNull(restored.getProposer());
        assertEquals(0, restored.getBlockNumber());
        assertTrue(restored.getTransactions().isEmpty());
    }

    // --- Disk persistence ---

    @Test
    void blockChainPersistsBlocksToDisk(@TempDir Path tempDir) {
        BlockChain chain = new BlockChain(tempDir.toString());
        chain.addBlock(genesis);

        Transaction tx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        BlockChainBlock block1 = BlockBuilder.build(List.of(tx), genesis, PROPOSER);
        chain.addBlock(block1);

        // Verify files were created
        File genesisFile = tempDir.resolve("block_0.json").toFile();
        File block1File = tempDir.resolve("block_1.json").toFile();
        assertTrue(genesisFile.exists(), "block_0.json should exist");
        assertTrue(block1File.exists(), "block_1.json should exist");

        // Verify persisted block can be deserialized
        try {
            String json = Files.readString(block1File.toPath());
            BlockChainBlock restored = BlockSerializer.fromJson(json);
            assertEquals(block1.getBlockHash(), restored.getBlockHash());
            assertEquals(1, restored.getBlockNumber());
            assertEquals(1, restored.getTransactions().size());
        } catch (Exception e) {
            fail("Failed to read persisted block: " + e.getMessage());
        }
    }

    @Test
    void blockChainWithoutPersistenceDoesNotCrash() {
        BlockChain chain = new BlockChain(); // no persistence dir
        chain.addBlock(genesis);
        assertEquals(1, chain.getHeight());
        assertEquals(genesis, chain.getLatestBlock());
    }

    // --- Helpers ---

    private static Transaction tx(Address from, Address to, long value,
                                  long gasPrice, long gasLimit, long nonce) {
        return new Transaction(from, to, BigInteger.valueOf(value), new byte[0],
                BigInteger.valueOf(gasPrice), BigInteger.valueOf(gasLimit), nonce, null);
    }

    private static void assertTransactionOrderEquals(List<Transaction> a, List<Transaction> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i).txHash(), b.get(i).txHash(),
                    "transaction at index " + i + " differs");
        }
    }
}
