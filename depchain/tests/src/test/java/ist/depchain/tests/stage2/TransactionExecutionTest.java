package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.common.Transaction;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.core.blockchain.TransactionReceipt;

/**
 * Unit tests for TransactionExecutor (Step 3).
 * Covers native DepCoin transfers, gas accounting, edge cases, and failure modes.
 * No network, keys, or consensus required - tests run against an in-memory world state.
 */
class TransactionExecutionTest {

    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger NATIVE_TRANSFER_GAS = BigInteger.valueOf(21_000);

    private static final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address PROPOSER = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

    private DepChainWorldState ws;
    private TransactionExecutor executor;

    @BeforeEach
    void setup() {
        ws = new DepChainWorldState();
        executor = new TransactionExecutor(ws);
    }

    // --- Successful native transfer ---

    @Test
    void successfulNativeTransfer() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 500, GAS_PRICE, GAS_LIMIT, 0);
        TransactionReceipt receipt = executor.execute(tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        assertEquals(NATIVE_TRANSFER_GAS, receipt.getGasUsed());

        // fee = min(1*21000, 1*21000) = 21000
        BigInteger expectedFee = GAS_PRICE.multiply(NATIVE_TRANSFER_GAS);
        assertEquals(expectedFee, receipt.getFee());

        // Alice: 100000 - 500 (value) - 21000 (fee) = 78500
        assertEquals(BigInteger.valueOf(78_500), ws.getBalance(ALICE));
        // Bob: 0 + 500 = 500
        assertEquals(BigInteger.valueOf(500), ws.getBalance(BOB));
        // Proposer: 0 + 21000 = 21000
        assertEquals(expectedFee, ws.getBalance(PROPOSER));
        // Alice nonce incremented
        assertEquals(1, ws.getNonce(ALICE));
    }

    // --- Nonce increments correctly across multiple txs ---

    @Test
    void nonceIncrementsOnEachExecution() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        for (int i = 0; i < 3; i++) {
            Transaction tx = nativeTransfer(ALICE, BOB, 100, GAS_PRICE, GAS_LIMIT, i);
            TransactionReceipt receipt = executor.execute(tx, PROPOSER);
            assertTrue(receipt.isSuccess(), "tx " + i + " failed: " + receipt.getError());
        }

        assertEquals(3, ws.getNonce(ALICE));
        assertEquals(BigInteger.valueOf(300), ws.getBalance(BOB));
    }

    // --- Gas refund when gasLimit > gasUsed ---

    @Test
    void refundsUnusedGasWhenGasLimitExceedsGasUsed() {
        BigInteger highGasLimit = BigInteger.valueOf(100_000);
        ws.createEOA(ALICE, 0, BigInteger.valueOf(200_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 1000, GAS_PRICE, highGasLimit, 0);
        TransactionReceipt receipt = executor.execute(tx, PROPOSER);

        assertTrue(receipt.isSuccess());

        // fee = min(1*100000, 1*21000) = 21000
        BigInteger expectedFee = GAS_PRICE.multiply(NATIVE_TRANSFER_GAS);
        assertEquals(expectedFee, receipt.getFee());

        // Alice: 200000 - 1000 (value) - 21000 (fee) = 178000
        // (the extra 79000 of reserved gas is refunded)
        assertEquals(BigInteger.valueOf(178_000), ws.getBalance(ALICE));
        assertEquals(BigInteger.valueOf(1000), ws.getBalance(BOB));
        assertEquals(expectedFee, ws.getBalance(PROPOSER));
    }

    // --- Out of gas: gasLimit < required gas ---

    @Test
    void outOfGasAbortsTransferButChargesFullGasLimit() {
        BigInteger tooLowGasLimit = BigInteger.valueOf(10_000); // less than 21000
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 500, GAS_PRICE, tooLowGasLimit, 0);
        TransactionReceipt receipt = executor.execute(tx, PROPOSER);

        assertFalse(receipt.isSuccess());
        assertNotNull(receipt.getError());
        assertTrue(receipt.getError().contains("out of gas"));

        // fee = gasLimit * gasPrice = 10000 (NOT refunded)
        BigInteger expectedFee = GAS_PRICE.multiply(tooLowGasLimit);
        assertEquals(expectedFee, receipt.getFee());

        // Alice: 100000 - 10000 (gas fee, not refunded) = 90000
        // Value is refunded because transfer was aborted
        assertEquals(BigInteger.valueOf(90_000), ws.getBalance(ALICE));
        // Bob gets nothing
        assertEquals(BigInteger.ZERO, ws.getBalance(BOB));
        // Proposer still gets the gas fee
        assertEquals(expectedFee, ws.getBalance(PROPOSER));
        // Nonce still incremented
        assertEquals(1, ws.getNonce(ALICE));
    }

    // --- Insufficient balance for upfront cost ---

    @Test
    void insufficientBalanceForUpfrontCostFails() {
        // Balance = 100, but upfront = value(500) + gasLimit*gasPrice(21000) = 21500
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100));
        ws.createEOA(BOB, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 500, GAS_PRICE, GAS_LIMIT, 0);
        TransactionReceipt receipt = executor.execute(tx, PROPOSER);

        assertFalse(receipt.isSuccess());
        assertTrue(receipt.getError().contains("insufficient balance"));

        // No balance changes, no fee charged
        assertEquals(BigInteger.valueOf(100), ws.getBalance(ALICE));
        assertEquals(BigInteger.ZERO, ws.getBalance(BOB));
        assertEquals(BigInteger.ZERO, receipt.getFee());
    }

    // --- Transfer to non-existent account creates it ---

    @Test
    void transferToNonExistentAccountCreatesIt() {
        Address unknown = Address.fromHexString("0xdddddddddddddddddddddddddddddddddddddddd");
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        assertFalse(ws.accountExists(unknown));

        Transaction tx = nativeTransfer(ALICE, unknown, 5000, GAS_PRICE, GAS_LIMIT, 0);
        TransactionReceipt receipt = executor.execute(tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        assertTrue(ws.accountExists(unknown));
        assertEquals(BigInteger.valueOf(5000), ws.getBalance(unknown));
    }

    // --- Zero value transfer still charges gas ---

    @Test
    void zeroValueTransferStillChargesGas() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.valueOf(50));
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 0, GAS_PRICE, GAS_LIMIT, 0);
        TransactionReceipt receipt = executor.execute(tx, PROPOSER);

        // Zero-value with no data is technically a native transfer
        // It should still succeed and charge gas
        assertTrue(receipt.isSuccess());
        BigInteger expectedFee = GAS_PRICE.multiply(NATIVE_TRANSFER_GAS);
        assertEquals(expectedFee, receipt.getFee());

        assertEquals(BigInteger.valueOf(100_000 - 21_000), ws.getBalance(ALICE));
        assertEquals(BigInteger.valueOf(50), ws.getBalance(BOB));
    }

    // --- Higher gas price means higher fee ---

    @Test
    void higherGasPriceIncreaseFee() {
        BigInteger highGasPrice = BigInteger.valueOf(10);
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 100, highGasPrice, GAS_LIMIT, 0);
        TransactionReceipt receipt = executor.execute(tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        // fee = min(10*21000, 10*21000) = 210000
        BigInteger expectedFee = highGasPrice.multiply(NATIVE_TRANSFER_GAS);
        assertEquals(expectedFee, receipt.getFee());

        assertEquals(BigInteger.valueOf(1_000_000 - 100 - 210_000), ws.getBalance(ALICE));
        assertEquals(expectedFee, ws.getBalance(PROPOSER));
    }

    // --- Proposer fee accumulates across multiple transactions ---

    @Test
    void proposerAccumulatesFees() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(500_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        BigInteger feePerTx = GAS_PRICE.multiply(NATIVE_TRANSFER_GAS);

        for (int i = 0; i < 5; i++) {
            Transaction tx = nativeTransfer(ALICE, BOB, 100, GAS_PRICE, GAS_LIMIT, i);
            assertTrue(executor.execute(tx, PROPOSER).isSuccess());
        }

        assertEquals(feePerTx.multiply(BigInteger.valueOf(5)), ws.getBalance(PROPOSER));
    }

    // --- Null proposer burns fees ---

    @Test
    void nullProposerBurnsFees() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 500, GAS_PRICE, GAS_LIMIT, 0);
        TransactionReceipt receipt = executor.execute(tx, null);

        assertTrue(receipt.isSuccess());
        // Alice still pays gas
        BigInteger expectedFee = GAS_PRICE.multiply(NATIVE_TRANSFER_GAS);
        assertEquals(BigInteger.valueOf(100_000 - 500 - 21_000), ws.getBalance(ALICE));
        assertEquals(BigInteger.valueOf(500), ws.getBalance(BOB));
        // Fee is burned (no proposer to credit)
        assertEquals(expectedFee, receipt.getFee());
    }

    // --- Contract deployment / call stubs return failure ---

    @Test
    void contractDeploymentReturnsFailureStub() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        // to=null means contract deployment
        Transaction tx = new Transaction(
                ALICE, null, BigInteger.ZERO, new byte[]{0x60, 0x60},
                GAS_PRICE, GAS_LIMIT, 0, null
        );

        TransactionReceipt receipt = executor.execute(tx, PROPOSER);
        assertFalse(receipt.isSuccess());
        assertTrue(receipt.getError().contains("not yet implemented"));
        // Nonce still incremented
        assertEquals(1, ws.getNonce(ALICE));
    }

    @Test
    void contractCallReturnsFailureStub() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        // to=BOB + non-empty data = contract call
        Transaction tx = new Transaction(
                ALICE, BOB, BigInteger.ZERO, new byte[]{0x01, 0x02, 0x03, 0x04},
                GAS_PRICE, GAS_LIMIT, 0, null
        );

        TransactionReceipt receipt = executor.execute(tx, PROPOSER);
        assertFalse(receipt.isSuccess());
        assertTrue(receipt.getError().contains("not yet implemented"));
        assertEquals(1, ws.getNonce(ALICE));
    }

    // --- Helper ---

    private static Transaction nativeTransfer(Address from, Address to, long value,
                                              BigInteger gasPrice, BigInteger gasLimit, long nonce) {
        return new Transaction(from, to, BigInteger.valueOf(value), new byte[0],
                gasPrice, gasLimit, nonce, null);
    }
}
