package ist.depchain.tests.stage2.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.common.Transaction;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.utils.Erc20Abi;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.EvmService;
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.tests.stage2.Stage2GasConstants;

/**
 * Guarantee: degenerate but technically valid inputs (self-transfers,
 * zero-address targets, circular fee accounting, contract calls to EOAs,
 * ERC-20 approve-to-zero, transferFrom-to-self, and world-state snapshot
 * independence) are handled deterministically and do not break invariants.
 *
 * All tests are unit-level - no network, keys or consensus required.
 */
class EdgeCaseRobustnessTest {

    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger NATIVE_GAS_COST = Stage2GasConstants.NATIVE_TRANSFER_GAS_COST;
    private static final BigInteger ERC20_GAS_LIMIT = BigInteger.valueOf(100_000);

    private static final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address PROPOSER = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");
    private static final Address ZERO_ADDRESS = Address.fromHexString("0x0000000000000000000000000000000000000000");
    private static final Address CONTRACT_ADDRESS = Address.fromHexString("0x9999999999999999999999999999999999999999");
    private static final String SIG_ALGO = "SHA256withECDSA";

    private DepChainWorldState ws;
    private TransactionExecutor executor;

    @BeforeEach
    void setup() {
        ws = new DepChainWorldState(null);
        executor = new TransactionExecutor();
    }

    // ==================== 1. Self-transfer (from == to) ====================

    /**
     * Native self-transfer: sender == receiver.
     * Balance must remain unchanged minus gas fee. Nonce must advance.
     */
    @Test
    void nativeSelfTransferPreservesBalanceMinusGas() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        Transaction tx = new Transaction(
                ALICE, ALICE, BigInteger.valueOf(500), new byte[0],
                GAS_PRICE, GAS_LIMIT, 0, null);

        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        BigInteger expectedFee = GAS_PRICE.multiply(NATIVE_GAS_COST);
        assertEquals(expectedFee, receipt.getFee());
        // Balance = 100_000 - fee (the 500 is subtracted then re-added)
        assertEquals(BigInteger.valueOf(100_000).subtract(expectedFee), ws.getBalance(ALICE));
        assertEquals(1, ws.getNonce(ALICE));
    }

    // ==================== 2. Transfer to zero address ====================

    /**
     * Native transfer to 0x0. The zero address should be created if it
     * doesn't exist, and receive the value. This tests that 0x0 is not
     * special-cased in any way that breaks determinism.
     */
    @Test
    void nativeTransferToZeroAddressCreatesAccountAndCreditsValue() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        assertFalse(ws.accountExists(ZERO_ADDRESS));

        Transaction tx = new Transaction(
                ALICE, ZERO_ADDRESS, BigInteger.valueOf(1_000), new byte[0],
                GAS_PRICE, GAS_LIMIT, 0, null);

        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertTrue(receipt.isSuccess());
        assertTrue(ws.accountExists(ZERO_ADDRESS));
        assertEquals(BigInteger.valueOf(1_000), ws.getBalance(ZERO_ADDRESS));

        BigInteger expectedFee = GAS_PRICE.multiply(NATIVE_GAS_COST);
        assertEquals(BigInteger.valueOf(100_000 - 1_000).subtract(expectedFee), ws.getBalance(ALICE));
    }

    // ==================== 3. ERC-20 approve to zero ====================

    /**
     * approve(spender, 0) when allowance is nonzero should succeed.
     * The ISTCoin contract only rejects non-zero -> non-zero transitions.
     */
    @Test
    void erc20ApproveToZeroSucceedsWhenAllowanceIsNonZero() throws Exception {
        setupErc20World();

        // Give treasury some tokens (already has initial supply from bootstrap)
        // increaseAllowance(alice, 100)
        execContractCall(treasury, CONTRACT_ADDRESS,
                "increaseAllowance(address,uint256)",
                Erc20Abi.encodeAddress(alice) + Erc20Abi.encodeUint256(BigInteger.valueOf(100)),
                treasuryKeys);

        assertEquals(BigInteger.valueOf(100), tokenAllowanceOf(treasury, alice));

        // approve(alice, 0) - should succeed (non-zero -> zero is allowed)
        TransactionReceipt receipt = execContractCall(treasury, CONTRACT_ADDRESS,
                "approve(address,uint256)",
                Erc20Abi.encodeAddress(alice) + Erc20Abi.encodeUint256(BigInteger.ZERO),
                treasuryKeys);

        assertTrue(receipt.isSuccess(), "approve to zero should succeed: " + receipt.getError());
        assertEquals(BigInteger.ZERO, tokenAllowanceOf(treasury, alice));
    }

    // ==================== 4. ERC-20 transferFrom(owner, owner, amount)
    // ====================

    /**
     * Owner uses their own allowance to transfer tokens to themselves.
     * Allowance should decrease but token balance should stay the same.
     */
    @Test
    void erc20TransferFromOwnerToOwnerDeductsAllowanceButPreservesBalance() throws Exception {
        setupErc20World();

        BigInteger initialBalance = tokenBalanceOf(treasury);

        // treasury increaseAllowance(treasury, 500) - owner approves themselves
        execContractCall(treasury, CONTRACT_ADDRESS,
                "increaseAllowance(address,uint256)",
                Erc20Abi.encodeAddress(treasury) + Erc20Abi.encodeUint256(BigInteger.valueOf(500)),
                treasuryKeys);

        assertEquals(BigInteger.valueOf(500), tokenAllowanceOf(treasury, treasury));

        // treasury transferFrom(treasury, treasury, 200) - self-transfer
        TransactionReceipt receipt = execContractCall(treasury, CONTRACT_ADDRESS,
                "transferFrom(address,address,uint256)",
                Erc20Abi.encodeAddress(treasury) + Erc20Abi.encodeAddress(treasury)
                        + Erc20Abi.encodeUint256(BigInteger.valueOf(200)),
                treasuryKeys);

        assertTrue(receipt.isSuccess(), receipt.getError());
        // Token balance unchanged (transferred to self)
        assertEquals(initialBalance, tokenBalanceOf(treasury));
        // Allowance consumed
        assertEquals(BigInteger.valueOf(300), tokenAllowanceOf(treasury, treasury));
    }

    // ==================== 5. Contract call to EOA (no code) ====================

    /**
     * Calling an EOA address with calldata should fail with "no runtime code"
     * and still charge gas. The state must not be corrupted.
     */
    @Test
    void contractCallToEoaFailsWithNoRuntimeCode() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(1_000_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);
        ws.createEOA(PROPOSER, 0, BigInteger.ZERO);

        // Craft a tx with calldata targeting BOB (an EOA, no contract code)
        byte[] fakeCalldata = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        Transaction tx = new Transaction(
                ALICE, BOB, BigInteger.ZERO, fakeCalldata,
                GAS_PRICE, BigInteger.valueOf(100_000), 0, null);

        TransactionReceipt receipt = executor.execute(ws, tx, PROPOSER);

        assertFalse(receipt.isSuccess());
        assertTrue(receipt.getError().contains("no runtime code"),
                "Expected 'no runtime code' error, got: " + receipt.getError());
        // Full gas limit charged (gasUsed = 0 -> shouldChargeFullGasLimit kicks in)
        BigInteger expectedFee = GAS_PRICE.multiply(BigInteger.valueOf(100_000));
        assertEquals(expectedFee, receipt.getFee());
        // Nonce still advanced
        assertEquals(1, ws.getNonce(ALICE));
    }

    // ==================== 6. World state snapshot independence
    // ====================

    /**
     * copy() must produce a fully independent snapshot. Mutations on the copy
     * must not affect the original, and vice versa.
     */
    @Test
    void worldStateCopyIsIndependent() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(50_000));
        ws.createEOA(BOB, 0, BigInteger.valueOf(30_000));

        DepChainWorldState snapshot = ws.copy();

        // Mutate the original
        ws.addBalance(ALICE, BigInteger.valueOf(10_000));
        ws.incrementNonce(ALICE);

        // Snapshot must be unaffected
        assertEquals(BigInteger.valueOf(50_000), snapshot.getBalance(ALICE));
        assertEquals(0, snapshot.getNonce(ALICE));

        // Mutate the snapshot
        snapshot.addBalance(BOB, BigInteger.valueOf(5_000));

        // Original must be unaffected
        assertEquals(BigInteger.valueOf(30_000), ws.getBalance(BOB));

        // Create account in original, should not appear in snapshot
        Address newAddr = Address.fromHexString("0xdddddddddddddddddddddddddddddddddddddddd");
        ws.createEOA(newAddr, 0, BigInteger.valueOf(1_000));
        assertFalse(snapshot.accountExists(newAddr));
    }

    /**
     * State hash must be stable: calling computeStateHash() twice without
     * changes must produce the same result.
     */
    @Test
    void stateHashIsStableWithoutModifications() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(50_000));
        ws.createEOA(BOB, 0, BigInteger.valueOf(30_000));

        String hash1 = ws.computeStateHash();
        String hash2 = ws.computeStateHash();
        assertEquals(hash1, hash2);
    }

    /**
     * State hash must differ after a balance change.
     */
    @Test
    void stateHashChangesAfterBalanceModification() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(50_000));

        String hashBefore = ws.computeStateHash();
        ws.addBalance(ALICE, BigInteger.ONE);
        String hashAfter = ws.computeStateHash();

        assertNotEquals(hashBefore, hashAfter);
    }

    // ==================== 7. Proposer == sender ====================

    /**
     * When the block proposer is also the transaction sender, fees paid by
     * the sender are credited back to the proposer (same account). Net effect:
     * sender loses only the transfer value, not gas.
     */
    @Test
    void proposerIsSenderFeesCreditedBack() {
        ws.createEOA(ALICE, 0, BigInteger.valueOf(100_000));
        ws.createEOA(BOB, 0, BigInteger.ZERO);

        // ALICE is both sender and proposer
        Transaction tx = new Transaction(
                ALICE, BOB, BigInteger.valueOf(1_000), new byte[0],
                GAS_PRICE, GAS_LIMIT, 0, null);

        TransactionReceipt receipt = executor.execute(ws, tx, ALICE);

        assertTrue(receipt.isSuccess());
        BigInteger fee = receipt.getFee();
        // Alice = 100_000 - 1_000 (value) - fee (gas) + fee (proposer credit) = 99_000
        assertEquals(BigInteger.valueOf(99_000), ws.getBalance(ALICE));
        assertEquals(BigInteger.valueOf(1_000), ws.getBalance(BOB));
    }

    // ==================== ERC-20 helpers ====================

    private KeyPair treasuryKeys;
    private KeyPair aliceKeys;
    private Address treasury;
    private Address alice;

    private void setupErc20World() throws Exception {
        DepChainWorldState erc20Ws = new DepChainWorldState(null);
        this.ws = erc20Ws;
        this.executor = new TransactionExecutor();

        treasuryKeys = genKeyPair();
        aliceKeys = genKeyPair();
        KeyPair proposerKeys = genKeyPair();

        treasury = AddressUtils.deriveAddress(treasuryKeys.getPublic());
        alice = AddressUtils.deriveAddress(aliceKeys.getPublic());
        Address proposerAddr = AddressUtils.deriveAddress(proposerKeys.getPublic());

        ws.createEOA(treasury, 0, new BigInteger("1000000000"));
        ws.createEOA(alice, 0, new BigInteger("1000000000"));
        ws.createEOA(proposerAddr, 0, BigInteger.ZERO);

        // Deploy ERC-20 contract
        String creationPath = "../core/src/main/resources/contracts/ist/ISTCoin.creation.bin";
        String creationBin = Files.readString(Path.of(creationPath)).trim();
        if (creationBin.startsWith("0x"))
            creationBin = creationBin.substring(2);

        String deploymentHex = creationBin + Erc20Abi.encodeAddress(treasury);

        EvmService.EvmResult deployment = EvmService.deployContract(
                ws, treasury, CONTRACT_ADDRESS,
                Bytes.fromHexString("0x" + deploymentHex).toArrayUnsafe());

        assertTrue(deployment.isSuccess(), deployment.getErrorMessage());
    }

    private TransactionReceipt execContractCall(Address from, Address contract,
            String method, String encodedArgs,
            KeyPair keys) throws Exception {
        byte[] calldata = Bytes.fromHexString(
                "0x" + Erc20Abi.smartContractMethodIdentifier(method) + encodedArgs).toArrayUnsafe();

        Transaction unsigned = new Transaction(
                from, contract, BigInteger.ZERO, calldata,
                GAS_PRICE, ERC20_GAS_LIMIT, ws.getNonce(from), null);

        byte[] sig = Crypto.sign(unsigned.toUnsignedBytes(), keys.getPrivate(), SIG_ALGO);
        Transaction signed = unsigned.withSignature(sig);

        return executor.execute(ws, signed, PROPOSER);
    }

    private BigInteger tokenBalanceOf(Address owner) {
        Bytes runtimeCode = ws.getCode(CONTRACT_ADDRESS);
        String calldata = "0x" + Erc20Abi.smartContractMethodIdentifier("balanceOf(address)")
                + Erc20Abi.encodeAddress(owner);
        return EvmService.callUint(ws, owner, CONTRACT_ADDRESS, runtimeCode, calldata);
    }

    private BigInteger tokenAllowanceOf(Address owner, Address spender) {
        Bytes runtimeCode = ws.getCode(CONTRACT_ADDRESS);
        String calldata = "0x" + Erc20Abi.smartContractMethodIdentifier("allowance(address,address)")
                + Erc20Abi.encodeAddress(owner) + Erc20Abi.encodeAddress(spender);
        return EvmService.callUint(ws, owner, CONTRACT_ADDRESS, runtimeCode, calldata);
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}
