package ist.depchain.tests.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;

import ist.depchain.common.Transaction;
import ist.depchain.core.blockchain.BlockBuilder;
import ist.depchain.core.blockchain.BlockChainBlock;

/**
 * Unit tests for BlockBuilder - orderTransactions(List).
 *
 * Validates that transactions are ordered by descending fee while strictly
 * preserving per-sender nonce ordering (nonce N must precede nonce N+1 for
 * the same sender).
 */
class BlockBuilderOrderingTest {

    private static final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address CAROL = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");
    private static final Address DAVE = Address.fromHexString("0xdddddddddddddddddddddddddddddddddddddd");

    // ==================== Single-sender tests ====================

    @Test
    void singleSender_orderedByNonceNotFee() {
        // Even though nonce 2 has a higher fee, nonce 0 and 1 must come first
        Transaction t0 = tx(ALICE, BOB, 100, 1, 21_000, 0); // fee = 21_000
        Transaction t1 = tx(ALICE, BOB, 100, 2, 21_000, 1); // fee = 42_000
        Transaction t2 = tx(ALICE, BOB, 100, 10, 21_000, 2); // fee = 210_000

        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(t2, t0, t1));

        assertEquals(3, ordered.size());
        assertEquals(0, ordered.get(0).getNonce());
        assertEquals(1, ordered.get(1).getNonce());
        assertEquals(2, ordered.get(2).getNonce());
    }

    @Test
    void singleSender_singleTransaction() {
        Transaction t0 = tx(ALICE, BOB, 100, 5, 21_000, 0);

        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(t0));

        assertEquals(1, ordered.size());
        assertEquals(0, ordered.get(0).getNonce());
    }

    // ==================== Multi-sender fee priority tests ====================

    @Test
    void multiSender_highestFeeFirstWhenNoncesAreIndependent() {
        // Each sender has a single tx - pure fee ordering should apply
        Transaction aliceTx = tx(ALICE, BOB, 100, 1, 21_000, 0); // fee = 21_000
        Transaction bobTx = tx(BOB, CAROL, 100, 10, 21_000, 0); // fee = 210_000
        Transaction carolTx = tx(CAROL, ALICE, 100, 5, 21_000, 0); // fee = 105_000

        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(aliceTx, carolTx, bobTx));

        assertEquals(3, ordered.size());
        assertEquals(BigInteger.valueOf(210_000), ordered.get(0).getMaxFee()); // Bob
        assertEquals(BigInteger.valueOf(105_000), ordered.get(1).getMaxFee()); // Carol
        assertEquals(BigInteger.valueOf(21_000), ordered.get(2).getMaxFee()); // Alice
    }

    @Test
    void multiSender_nonceConstraintDelaysHighFeeTx() {
        // Alice: nonce 0 (low fee), nonce 1 (very high fee)
        // Bob: nonce 0 (medium fee)
        // Round 1 eligible: Alice(0)=21k, Bob(0)=100k -> pick Bob(0)
        // Round 2 eligible: Alice(0)=21k -> pick Alice(0)
        // Round 3 eligible: Alice(1)=500k -> pick Alice(1)
        Transaction alice0 = tx(ALICE, BOB, 100, 1, 21_000, 0); // fee = 21_000
        Transaction alice1 = tx(ALICE, BOB, 100, 500_000, 1, 1); // fee = 500_000
        Transaction bob0 = tx(BOB, CAROL, 100, 100_000, 1, 0); // fee = 100_000

        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(alice1, alice0, bob0));

        assertEquals(3, ordered.size());
        // Bob's tx has highest eligible fee in round 1
        assertSender(BOB, ordered.get(0));
        assertEquals(0, ordered.get(0).getNonce());
        // Alice nonce 0 is next eligible
        assertSender(ALICE, ordered.get(1));
        assertEquals(0, ordered.get(1).getNonce());
        // Alice nonce 1 is now eligible
        assertSender(ALICE, ordered.get(2));
        assertEquals(1, ordered.get(2).getNonce());
    }

    // ==================== Nonce ordering invariant ====================

    @Test
    void nonceOrderIsStrictlyPreservedPerSender() {
        // Multiple senders with multiple nonces, varying fees
        Transaction alice0 = tx(ALICE, BOB, 100, 3, 21_000, 0);
        Transaction alice1 = tx(ALICE, BOB, 100, 20, 21_000, 1);
        Transaction alice2 = tx(ALICE, BOB, 100, 1, 21_000, 2);
        Transaction bob0 = tx(BOB, CAROL, 100, 10, 21_000, 0);
        Transaction bob1 = tx(BOB, CAROL, 100, 50, 21_000, 1);
        Transaction carol0 = tx(CAROL, ALICE, 100, 7, 21_000, 0);

        List<Transaction> ordered = BlockBuilder.orderTransactions(
                List.of(alice2, bob1, carol0, alice0, bob0, alice1));

        assertEquals(6, ordered.size());
        assertNonceOrderPerSender(ordered, ALICE);
        assertNonceOrderPerSender(ordered, BOB);
        assertNonceOrderPerSender(ordered, CAROL);
    }

    @Test
    void nonceGaps_allTransactionsStillIncluded() {
        // Nonces don't have to start at 0 or be contiguous;
        // the builder just needs to maintain relative order
        Transaction alice5 = tx(ALICE, BOB, 100, 10, 21_000, 5);
        Transaction alice10 = tx(ALICE, BOB, 100, 1, 21_000, 10);
        Transaction alice7 = tx(ALICE, BOB, 100, 50, 21_000, 7);

        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(alice10, alice5, alice7));

        assertEquals(3, ordered.size());
        assertEquals(5, ordered.get(0).getNonce());
        assertEquals(7, ordered.get(1).getNonce());
        assertEquals(10, ordered.get(2).getNonce());
    }

    // ==================== Determinism tests ====================

    @Test
    void orderingIsDeterministicRegardlessOfInputOrder() {
        Transaction alice0 = tx(ALICE, BOB, 100, 3, 21_000, 0);
        Transaction alice1 = tx(ALICE, BOB, 100, 20, 21_000, 1);
        Transaction bob0 = tx(BOB, CAROL, 100, 10, 21_000, 0);
        Transaction carol0 = tx(CAROL, ALICE, 100, 7, 21_000, 0);

        List<Transaction> input1 = List.of(alice0, alice1, bob0, carol0);
        List<Transaction> input2 = List.of(carol0, bob0, alice1, alice0);
        List<Transaction> input3 = List.of(bob0, alice0, carol0, alice1);

        List<Transaction> ordered1 = BlockBuilder.orderTransactions(input1);
        List<Transaction> ordered2 = BlockBuilder.orderTransactions(input2);
        List<Transaction> ordered3 = BlockBuilder.orderTransactions(input3);

        assertTransactionOrderEquals(ordered1, ordered2);
        assertTransactionOrderEquals(ordered2, ordered3);
    }

    @Test
    void shuffledInputProducesSameOutput() {
        List<Transaction> txs = new ArrayList<>();
        txs.add(tx(ALICE, BOB, 100, 5, 21_000, 0));
        txs.add(tx(ALICE, BOB, 100, 3, 21_000, 1));
        txs.add(tx(ALICE, BOB, 100, 8, 21_000, 2));
        txs.add(tx(BOB, CAROL, 100, 12, 21_000, 0));
        txs.add(tx(BOB, CAROL, 100, 1, 21_000, 1));
        txs.add(tx(CAROL, ALICE, 100, 20, 21_000, 0));

        List<Transaction> reference = BlockBuilder.orderTransactions(new ArrayList<>(txs));

        // Shuffle multiple times and verify same result
        for (int i = 0; i < 10; i++) {
            List<Transaction> shuffled = new ArrayList<>(txs);
            Collections.shuffle(shuffled);
            List<Transaction> result = BlockBuilder.orderTransactions(shuffled);
            assertTransactionOrderEquals(reference, result);
        }
    }

    // ==================== Tie-breaking tests ====================

    @Test
    void sameFeeFromDifferentSenders_tiebrokenByTxHash() {
        // Same fee, different senders - deterministic tie-break by tx hash
        Transaction aliceTx = tx(ALICE, BOB, 100, 1, 21_000, 0);
        Transaction bobTx = tx(BOB, CAROL, 100, 1, 21_000, 0);

        assertEquals(aliceTx.getMaxFee(), bobTx.getMaxFee());

        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(bobTx, aliceTx));

        assertEquals(2, ordered.size());
        // Verify ascending tx hash order for tie-break
        String hash0 = Numeric.toHexStringNoPrefix(ordered.get(0).txHash());
        String hash1 = Numeric.toHexStringNoPrefix(ordered.get(1).txHash());
        assertTrue(hash0.compareTo(hash1) <= 0,
                "tie-breaker should sort by tx hash ascending");
    }

    // ==================== Edge cases ====================

    @Test
    void emptyTransactionList() {
        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of());
        assertTrue(ordered.isEmpty());
    }

    @Test
    void singleTransaction() {
        Transaction t = tx(ALICE, BOB, 100, 5, 21_000, 0);
        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(t));
        assertEquals(1, ordered.size());
        assertArrayEquals(t.txHash(), ordered.get(0).txHash());
    }

    @Test
    void allTransactionsSameFeeAndSameSender() {
        // Same sender, same fee, different nonces - must still be nonce-ordered
        Transaction t0 = tx(ALICE, BOB, 100, 1, 21_000, 0);
        Transaction t1 = tx(ALICE, BOB, 200, 1, 21_000, 1);
        Transaction t2 = tx(ALICE, BOB, 300, 1, 21_000, 2);

        List<Transaction> ordered = BlockBuilder.orderTransactions(List.of(t2, t0, t1));

        assertEquals(0, ordered.get(0).getNonce());
        assertEquals(1, ordered.get(1).getNonce());
        assertEquals(2, ordered.get(2).getNonce());
    }

    // ==================== Larger scenario ====================

    @Test
    void fourSendersInterleavedByFeeAndNonce() {
        // Alice: nonce 0 (fee=100k), nonce 1 (fee=50k), nonce 2 (fee=300k)
        // Bob: nonce 0 (fee=200k), nonce 1 (fee=10k)
        // Carol: nonce 0 (fee=150k)
        // Dave: nonce 0 (fee=80k), nonce 1 (fee=400k)
        Transaction alice0 = tx(ALICE, BOB, 0, 100_000, 1, 0);
        Transaction alice1 = tx(ALICE, BOB, 0, 50_000, 1, 1);
        Transaction alice2 = tx(ALICE, BOB, 0, 300_000, 1, 2);
        Transaction bob0 = tx(BOB, CAROL, 0, 200_000, 1, 0);
        Transaction bob1 = tx(BOB, CAROL, 0, 10_000, 1, 1);
        Transaction carol0 = tx(CAROL, ALICE, 0, 150_000, 1, 0);
        Transaction dave0 = tx(DAVE, ALICE, 0, 80_000, 1, 0);
        Transaction dave1 = tx(DAVE, ALICE, 0, 400_000, 1, 1);

        List<Transaction> ordered = BlockBuilder.orderTransactions(
                List.of(dave1, alice2, bob1, carol0, alice0, dave0, bob0, alice1));

        assertEquals(8, ordered.size());

        // Verify nonce ordering is preserved for every sender
        assertNonceOrderPerSender(ordered, ALICE);
        assertNonceOrderPerSender(ordered, BOB);
        assertNonceOrderPerSender(ordered, CAROL);
        assertNonceOrderPerSender(ordered, DAVE);

        // Walk through expected greedy selection:
        // Round 1 eligible: Alice(0)=100k, Bob(0)=200k, Carol(0)=150k, Dave(0)=80k ->
        // Bob(0)
        assertSender(BOB, ordered.get(0));
        assertEquals(0, ordered.get(0).getNonce());

        // Round 2 eligible: Alice(0)=100k, Bob(1)=10k, Carol(0)=150k, Dave(0)=80k ->
        // Carol(0)
        assertSender(CAROL, ordered.get(1));
        assertEquals(0, ordered.get(1).getNonce());

        // Round 3 eligible: Alice(0)=100k, Bob(1)=10k, Dave(0)=80k -> Alice(0)
        assertSender(ALICE, ordered.get(2));
        assertEquals(0, ordered.get(2).getNonce());

        // Round 4 eligible: Alice(1)=50k, Bob(1)=10k, Dave(0)=80k -> Dave(0)
        assertSender(DAVE, ordered.get(3));
        assertEquals(0, ordered.get(3).getNonce());

        // Round 5 eligible: Alice(1)=50k, Bob(1)=10k, Dave(1)=400k -> Dave(1)
        assertSender(DAVE, ordered.get(4));
        assertEquals(1, ordered.get(4).getNonce());

        // Round 6 eligible: Alice(1)=50k, Bob(1)=10k -> Alice(1)
        assertSender(ALICE, ordered.get(5));
        assertEquals(1, ordered.get(5).getNonce());

        // Round 7 eligible: Alice(2)=300k, Bob(1)=10k -> Alice(2)
        assertSender(ALICE, ordered.get(6));
        assertEquals(2, ordered.get(6).getNonce());

        // Round 8 eligible: Bob(1)=10k -> Bob(1)
        assertSender(BOB, ordered.get(7));
        assertEquals(1, ordered.get(7).getNonce());
    }

    // ==================== Integration with BlockBuilder.build ====================

    @Test
    void buildProducesNonceOrderedBlock() {
        Address proposer = Address.fromHexString("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        BlockChainBlock genesis = new BlockChainBlock("0x0000", null, List.of(), 0);

        Transaction alice0 = tx(ALICE, BOB, 100, 1, 21_000, 0);
        Transaction alice1 = tx(ALICE, BOB, 100, 50, 21_000, 1);
        Transaction bob0 = tx(BOB, CAROL, 100, 10, 21_000, 0);

        BlockChainBlock block = BlockBuilder.build(List.of(alice1, bob0, alice0), genesis, proposer);

        List<Transaction> ordered = block.getTransactions();
        assertEquals(3, ordered.size());
        assertNonceOrderPerSender(ordered, ALICE);
        assertNonceOrderPerSender(ordered, BOB);
    }

    // ==================== Helpers ====================

    private static Transaction tx(Address from, Address to, long value,
            long gasPrice, long gasLimit, long nonce) {
        return new Transaction(from, to, BigInteger.valueOf(value), new byte[0],
                BigInteger.valueOf(gasPrice), BigInteger.valueOf(gasLimit), nonce, null);
    }

    private static void assertSender(Address expected, Transaction tx) {
        assertEquals(expected, tx.getFrom(),
                "expected sender " + expected + " but got " + tx.getFrom());
    }

    private static void assertNonceOrderPerSender(List<Transaction> ordered, Address sender) {
        long lastNonce = -1;
        for (Transaction tx : ordered) {
            if (tx.getFrom().equals(sender)) {
                assertTrue(tx.getNonce() > lastNonce,
                        "nonce ordering violated for " + sender +
                                ": nonce " + tx.getNonce() + " appeared after " + lastNonce);
                lastNonce = tx.getNonce();
            }
        }
    }

    private static void assertTransactionOrderEquals(List<Transaction> a, List<Transaction> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i).txHash(), b.get(i).txHash(),
                    "transaction at index " + i + " differs");
        }
    }
}
