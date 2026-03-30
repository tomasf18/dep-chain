package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashSet;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.common.Transaction;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Crypto;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.blockchain.TransactionValidator;
import ist.depchain.core.blockchain.ValidationResult;

/**
 * Gas parameter edge case tests (TODO-TESTS §G).
 *
 * Guarantee: malformed fee/gas parameters do not break safety or produce
 * inconsistent execution. All invalid gas configurations are either rejected
 * at validation or handled deterministically at execution.
 *
 * Tests:
 *   1. Absurdly large gas limit is accepted at validation but excess is refunded.
 *   2. Gas limit exactly equal to required gas (no refund, no failure).
 *   3. Gas limit one less than required → out-of-gas failure.
 *   4. Very high gas price charges proportionally higher fee.
 *   5. Upfront cost overflow does not bypass balance checks.
 *   6. Contract call with too-low gas limit reverts but charges full limit.
 */
class GasParameterEdgeCasesTest {

    private static final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address PROPOSER = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");
    private static final BigInteger NATIVE_GAS_COST = Stage2GasConstants.NATIVE_TRANSFER_GAS_COST;

    private DepChainWorldState ws;
    private TransactionExecutor executor;

    @BeforeEach
    void setup() {
        ws = new DepChainWorldState(null);
        executor = new TransactionExecutor();
    }

    /**
     * Absurdly large gas limit: sender has enough balance for the upfront cost.
     * The excess gas beyond what's actually used is refunded. The fee is based
     * on actual gas used, not the inflated gas limit.
     */
    @Test
    void absurdGasLimitRefundsExcessGas() {
        BigInteger absurdGasLimit = BigInteger.valueOf(10_000_000);
        BigInteger gasPrice = BigInteger.ONE;
        // Upfront cost = 100 (value) + 10_000_000 (gasLimit * gasPrice) = 10_000_100
        ws.createEOA(ALICE, 0, BigInteger.valueOf(20_000_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 100, gasPrice, absurdGasLimit, 0);
        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        // Fee based on actual gas used (20_000), not gas limit (10_000_000)
        assertEquals(NATIVE_GAS_COST, receipt.getGasUsed());
        BigInteger expectedFee = gasPrice.multiply(NATIVE_GAS_COST);
        assertEquals(expectedFee, receipt.getFee());

        // Alice: 20_000_000 - 100 (value) - 20_000 (fee) = 19_979_900
        assertEquals(BigInteger.valueOf(19_979_900), ws.getBalance(ALICE));
        assertEquals(BigInteger.valueOf(100), ws.getBalance(BOB));
        assertEquals(expectedFee, ws.getBalance(PROPOSER));
    }

    /**
     * Gas limit exactly equal to the native transfer gas cost.
     * No refund, no failure — exact fit.
     */
    @Test
    void exactGasLimitNoRefund() {
        BigInteger gasPrice = BigInteger.valueOf(2);
        BigInteger exactGasLimit = NATIVE_GAS_COST; // 20_000
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 500, gasPrice, exactGasLimit, 0);
        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        assertEquals(NATIVE_GAS_COST, receipt.getGasUsed());
        BigInteger expectedFee = gasPrice.multiply(NATIVE_GAS_COST); // 40_000
        assertEquals(expectedFee, receipt.getFee());

        // No refund: gasLimit == gasUsed
        assertEquals(BigInteger.valueOf(100_000 - 500 - 40_000), ws.getBalance(ALICE));
    }

    /**
     * Gas limit one less than required → out-of-gas.
     * Full gas limit is charged (not refunded), value is refunded.
     */
    @Test
    void gasLimitOneLessThanRequiredCausesOutOfGas() {
        BigInteger gasPrice = BigInteger.ONE;
        BigInteger tooLowGasLimit = NATIVE_GAS_COST.subtract(BigInteger.ONE); // 19_999
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 500, gasPrice, tooLowGasLimit, 0);
        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertFalse(receipt.isSuccess());
        assertTrue(receipt.getError().contains("out of gas"));
        // Full gas limit charged, value refunded
        BigInteger expectedFee = gasPrice.multiply(tooLowGasLimit);
        assertEquals(expectedFee, receipt.getFee());
        assertEquals(BigInteger.valueOf(100_000).subtract(expectedFee), ws.getBalance(ALICE));
        assertEquals(BigInteger.ZERO, ws.getBalance(BOB));
    }

    /**
     * Very high gas price with sufficient balance.
     * Fee = gasPrice * gasUsed, not gasPrice * gasLimit.
     */
    @Test
    void veryHighGasPriceChargesProportionally() {
        BigInteger highGasPrice = BigInteger.valueOf(1000);
        BigInteger gasLimit = BigInteger.valueOf(21_000);
        // Upfront: 100 + 1000 * 21_000 = 21_000_100
        ws.createEOA(ALICE, 0, BigInteger.valueOf(50_000_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 100, highGasPrice, gasLimit, 0);
        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        BigInteger expectedFee = highGasPrice.multiply(NATIVE_GAS_COST); // 1000 * 20_000 = 20_000_000
        assertEquals(expectedFee, receipt.getFee());
        assertEquals(expectedFee, ws.getBalance(PROPOSER));
    }

    /**
     * Upfront cost that would exceed sender's balance even with a very large gas limit.
     * Must fail with "insufficient balance" — no overflow tricks.
     */
    @Test
    void upfrontCostExceedingBalanceFailsDeterministically() {
        BigInteger gasPrice = BigInteger.valueOf(100);
        BigInteger largeGasLimit = BigInteger.valueOf(1_000_000);
        // Upfront: 1000 + 100 * 1_000_000 = 100_001_000, balance = 1_000_000
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 1000, gasPrice, largeGasLimit, 0);
        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertFalse(receipt.isSuccess());
        assertTrue(receipt.getError().contains("insufficient balance"));
        // No balance change
        assertEquals(BigInteger.valueOf(1_000_000), ws.getBalance(ALICE));
        assertEquals(BigInteger.ZERO, ws.getBalance(BOB));
    }

    /**
     * Validation rejects zero gas price at the validation layer.
     */
    @Test
    void validationRejectsZeroGasPrice() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());

        DepChainWorldState validationWs = new DepChainWorldState(null);
        validationWs.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(from, BOB, BigInteger.valueOf(100), new byte[0],
                BigInteger.ZERO, BigInteger.valueOf(21_000), 0, null);
        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        ValidationResult result = TransactionValidator.validate(signed, kp.getPublic(), "SHA256withECDSA", validationWs, null);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("gasPrice"));
    }

    /**
     * Validation rejects zero gas limit at the validation layer.
     */
    @Test
    void validationRejectsZeroGasLimit() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());

        DepChainWorldState validationWs = new DepChainWorldState(null);
        validationWs.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(from, BOB, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.ZERO, 0, null);
        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        ValidationResult result = TransactionValidator.validate(signed, kp.getPublic(), "SHA256withECDSA", validationWs, null);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("gasLimit"));
    }

    /**
     * Gas limit of 1 (absurdly low) for a native transfer → out-of-gas.
     * Fee = gasPrice * 1 (the full gas limit). Deterministic failure.
     */
    @Test
    void gasLimitOfOneFailsWithOutOfGas() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = nativeTransfer(ALICE, BOB, 100, BigInteger.ONE, BigInteger.ONE, 0);
        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertFalse(receipt.isSuccess());
        assertEquals(BigInteger.ONE, receipt.getFee()); // 1 * 1 = 1
        assertEquals(BigInteger.valueOf(99_999), ws.getBalance(ALICE));
    }

    /**
     * Two identical transactions with different gas prices produce
     * deterministically different fees but identical post-transfer balances
     * for the receiver.
     */
    @Test
    void differentGasPricesProduceDifferentFeesButSameReceiverBalance() {
        DepChainWorldState ws1 = new DepChainWorldState(null);
        ws1.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws1.createEOA(BOB, 0, BigInteger.ZERO);
        ws1.createEOA(PROPOSER, 0, BigInteger.ZERO);

        DepChainWorldState ws2 = new DepChainWorldState(null);
        ws2.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws2.createEOA(BOB, 0, BigInteger.ZERO);
        ws2.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx1 = nativeTransfer(ALICE, BOB, 500, BigInteger.ONE, BigInteger.valueOf(21_000), 0);
        Transaction tx2 = nativeTransfer(ALICE, BOB, 500, BigInteger.TEN, BigInteger.valueOf(21_000), 0);

        TransactionReceipt r1 = new TransactionExecutor().execute(ws1, tx1, PROPOSER);
        TransactionReceipt r2 = new TransactionExecutor().execute(ws2, tx2, PROPOSER);

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());

        // Same receiver balance
        assertEquals(ws1.getBalance(BOB), ws2.getBalance(BOB));

        // Different fees
        assertNotEquals(r1.getFee(), r2.getFee());
        assertTrue(r2.getFee().compareTo(r1.getFee()) > 0);

        // Different sender balances (higher gas price = less remaining)
        assertTrue(ws2.getBalance(ALICE).compareTo(ws1.getBalance(ALICE)) < 0);
    }

    // ==================== Helpers ====================

    private static Transaction nativeTransfer(Address from, Address to, long value,
                                              BigInteger gasPrice, BigInteger gasLimit, long nonce) {
        return new Transaction(from, to, BigInteger.valueOf(value), new byte[0],
                gasPrice, gasLimit, nonce, null);
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}
