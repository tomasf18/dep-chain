package ist.depchain.tests.stage2;

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
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.blockchain.EvmService;

class TransactionExecutorErc20Test {

    private static final BigInteger INITIAL_NATIVE_BALANCE = new BigInteger("1000000000");
    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = new BigInteger("100000");
    private static final Address CONTRACT_ADDRESS =
            Address.fromHexString("0x9999999999999999999999999999999999999999");
    private static final String SIG_ALGO = "SHA256withECDSA";

    private DepChainWorldState worldState;
    private TransactionExecutor executor;

    private KeyPair treasuryKeys;
    private KeyPair aliceKeys;
    private KeyPair bobKeys;
    private KeyPair proposerKeys;

    private Address treasury;
    private Address alice;
    private Address bob;
    private Address proposer;

    @BeforeEach
    void setUp() throws Exception {
        worldState = new DepChainWorldState();
        executor = new TransactionExecutor();

        treasuryKeys = genKeyPair();
        aliceKeys = genKeyPair();
        bobKeys = genKeyPair();
        proposerKeys = genKeyPair();

        treasury = AddressUtils.deriveAddress(treasuryKeys.getPublic());
        alice = AddressUtils.deriveAddress(aliceKeys.getPublic());
        bob = AddressUtils.deriveAddress(bobKeys.getPublic());
        proposer = AddressUtils.deriveAddress(proposerKeys.getPublic());

        // Native balances
        worldState.createEOA(treasury, 0, INITIAL_NATIVE_BALANCE);
        worldState.createEOA(alice, 0, INITIAL_NATIVE_BALANCE);
        worldState.createEOA(bob, 0, INITIAL_NATIVE_BALANCE);
        worldState.createEOA(proposer, 0, BigInteger.ZERO);

        // Bootstrap IST contract into world state
        bootstrapIstContract();
    }

    @Test
    void erc20TransferUpdatesTokenBalancesAndChargesNativeFee() throws Exception {
        BigInteger treasuryNativeBefore = worldState.getBalance(treasury);
        BigInteger proposerNativeBefore = worldState.getBalance(proposer);

        // transfer(alice, 5000)
        byte[] calldata = Bytes.fromHexString(
                "0x" + Erc20Abi.smartContractMethodIdentifier("transfer(address,uint256)")
                        + Erc20Abi.encodeAddress(alice)
						+ Erc20Abi.encodeUint256(BigInteger.valueOf(5000))
        ).toArrayUnsafe();

        Transaction tx = signTx(new Transaction(
                treasury,
                CONTRACT_ADDRESS,
                BigInteger.ZERO,
                calldata,
                GAS_PRICE,
                GAS_LIMIT,
                worldState.getNonce(treasury),
                null
        ), treasuryKeys);

        TransactionReceipt receipt = executor.execute(worldState, tx, proposer);

        assertTrue(receipt.isSuccess(), receipt.getError());
        assertEquals(GAS_LIMIT, receipt.getGasUsed());
        assertEquals(GAS_LIMIT.multiply(GAS_PRICE), receipt.getFee());

        BigInteger treasuryToken = tokenBalanceOf(treasury);
        BigInteger aliceToken = tokenBalanceOf(alice);

        assertEquals(new BigInteger("9999995000"), treasuryToken);
        assertEquals(BigInteger.valueOf(5000), aliceToken);

        // Native gas fee moved from treasury to proposer
        assertEquals(
                treasuryNativeBefore.subtract(GAS_LIMIT.multiply(GAS_PRICE)),
                worldState.getBalance(treasury)
        );
        assertEquals(
                proposerNativeBefore.add(GAS_LIMIT.multiply(GAS_PRICE)),
                worldState.getBalance(proposer)
        );
    }

    @Test
    void increaseAllowanceThenTransferFromWorks() throws Exception {
        // 1. Treasury transfers some tokens to Alice first
        byte[] transferToAlice = Bytes.fromHexString(
                "0x" + Erc20Abi.smartContractMethodIdentifier("transfer(address,uint256)")
                        + Erc20Abi.encodeAddress(alice)
                        + Erc20Abi.encodeUint256(BigInteger.valueOf(1000))
        ).toArrayUnsafe();

        Transaction t1 = signTx(new Transaction(
                treasury,
                CONTRACT_ADDRESS,
                BigInteger.ZERO,
                transferToAlice,
                GAS_PRICE,
                GAS_LIMIT,
                worldState.getNonce(treasury),
                null
        ), treasuryKeys);

        TransactionReceipt r1 = executor.execute(worldState, t1, proposer);
        assertTrue(r1.isSuccess(), r1.getError());

        assertEquals(BigInteger.valueOf(1000), tokenBalanceOf(alice));

        // 2. Alice increaseAllowance(bob, 400)
        byte[] incAllowance = Bytes.fromHexString(
                "0x" + Erc20Abi.smartContractMethodIdentifier("increaseAllowance(address,uint256)")
                        + Erc20Abi.encodeAddress(bob)
                        + Erc20Abi.encodeUint256(BigInteger.valueOf(400))
        ).toArrayUnsafe();

        Transaction t2 = signTx(new Transaction(
                alice,
                CONTRACT_ADDRESS,
                BigInteger.ZERO,
                incAllowance,
                GAS_PRICE,
                GAS_LIMIT,
                worldState.getNonce(alice),
                null
        ), aliceKeys);

        TransactionReceipt r2 = executor.execute(worldState, t2, proposer);
        assertTrue(r2.isSuccess(), r2.getError());

        assertEquals(BigInteger.valueOf(400), tokenAllowanceOf(alice, bob));

        // 3. Bob transferFrom(alice, bob, 250)
        byte[] transferFrom = Bytes.fromHexString(
                "0x" + Erc20Abi.smartContractMethodIdentifier("transferFrom(address,address,uint256)")
                        + Erc20Abi.encodeAddress(alice)
                        + Erc20Abi.encodeAddress(bob)
                        + Erc20Abi.encodeUint256(BigInteger.valueOf(250))
        ).toArrayUnsafe();

        Transaction t3 = signTx(new Transaction(
                bob,
                CONTRACT_ADDRESS,
                BigInteger.ZERO,
                transferFrom,
                GAS_PRICE,
                GAS_LIMIT,
                worldState.getNonce(bob),
                null
        ), bobKeys);

        TransactionReceipt r3 = executor.execute(worldState, t3, proposer);
        assertTrue(r3.isSuccess(), r3.getError());

        assertEquals(BigInteger.valueOf(750), tokenBalanceOf(alice));
        assertEquals(BigInteger.valueOf(250), tokenBalanceOf(bob));
        assertEquals(BigInteger.valueOf(150), tokenAllowanceOf(alice, bob));
    }

    @Test
    void contractCallRevertProducesFailedReceipt() throws Exception {
        // Alice has zero tokens initially, so transfer(bob,1) should revert/fail
        byte[] transfer = Bytes.fromHexString(
                "0x" + Erc20Abi.smartContractMethodIdentifier("transfer(address,uint256)")
                        + Erc20Abi.encodeAddress(bob)
                        + Erc20Abi.encodeUint256(BigInteger.ONE)
        ).toArrayUnsafe();

        Transaction tx = signTx(new Transaction(
                alice,
                CONTRACT_ADDRESS,
                BigInteger.ZERO,
                transfer,
                GAS_PRICE,
                GAS_LIMIT,
                worldState.getNonce(alice),
                null
        ), aliceKeys);

        TransactionReceipt receipt = executor.execute(worldState, tx, proposer);

        assertFalse(receipt.isSuccess());
        assertNotNull(receipt.getError());
        assertFalse(receipt.getError().isBlank());

        // Native gas is still charged
        assertEquals(
                INITIAL_NATIVE_BALANCE.subtract(GAS_LIMIT.multiply(GAS_PRICE)),
                worldState.getBalance(alice)
        );

        // No token transfer happened
        assertEquals(BigInteger.ZERO, tokenBalanceOf(bob));
    }

    // -------------------------
    // Helpers
    // -------------------------

    private void bootstrapIstContract() throws Exception {
        String creationPath = "../core/src/main/resources/contracts/ist/ISTCoin.creation.bin";
        String creationBin = Files.readString(Path.of(creationPath)).trim();
        if (creationBin.startsWith("0x")) {
            creationBin = creationBin.substring(2);
        }

        String deploymentHex = creationBin + Erc20Abi.encodeAddress(treasury);

        EvmService.EvmResult deployment = EvmService.deployContract(
                worldState,
                treasury,
                CONTRACT_ADDRESS,
                Bytes.fromHexString("0x" + deploymentHex).toArrayUnsafe()
        );

        assertTrue(deployment.isSuccess(), deployment.getErrorMessage());
        assertTrue(worldState.accountExists(CONTRACT_ADDRESS));
        assertFalse(worldState.getCode(CONTRACT_ADDRESS).isEmpty());

        // Sanity check bootstrap
        assertEquals("IST Coin", tokenName());
        assertEquals("IST", tokenSymbol());
        assertEquals(BigInteger.valueOf(2), tokenDecimals());
        assertEquals(new BigInteger("10000000000"), tokenTotalSupply());
        assertEquals(new BigInteger("10000000000"), tokenBalanceOf(treasury));
    }

    private Transaction signTx(Transaction unsignedTx, KeyPair keyPair) throws Exception {
        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), keyPair.getPrivate(), SIG_ALGO);
        return unsignedTx.withSignature(sig);
    }

    private BigInteger tokenBalanceOf(Address owner) {
        Bytes runtimeCode = worldState.getCode(CONTRACT_ADDRESS);
        String calldata = "0x" + Erc20Abi.smartContractMethodIdentifier("balanceOf(address)") + Erc20Abi.encodeAddress(owner);
        return EvmService.callUint(worldState, owner, CONTRACT_ADDRESS, runtimeCode, calldata);
    }

    private BigInteger tokenAllowanceOf(Address owner, Address spender) {
        Bytes runtimeCode = worldState.getCode(CONTRACT_ADDRESS);
        String calldata = "0x" + Erc20Abi.smartContractMethodIdentifier("allowance(address,address)")
                + Erc20Abi.encodeAddress(owner) 
                + Erc20Abi.encodeAddress(spender);
        return EvmService.callUint(worldState, owner, CONTRACT_ADDRESS, runtimeCode, calldata);
    }

    private String tokenName() {
        Bytes runtimeCode = worldState.getCode(CONTRACT_ADDRESS);
        return EvmService.callString(
                worldState,
                treasury,
                CONTRACT_ADDRESS,
                runtimeCode,
                "0x" + Erc20Abi.smartContractMethodIdentifier("name()")
        );
    }

    private String tokenSymbol() {
        Bytes runtimeCode = worldState.getCode(CONTRACT_ADDRESS);
        return EvmService.callString(
                worldState,
                treasury,
                CONTRACT_ADDRESS,
                runtimeCode,
                "0x" + Erc20Abi.smartContractMethodIdentifier("symbol()")
        );
    }

    private BigInteger tokenDecimals() {
        Bytes runtimeCode = worldState.getCode(CONTRACT_ADDRESS);
        return EvmService.callUint(
                worldState,
                treasury,
                CONTRACT_ADDRESS,
                runtimeCode,
                "0x" + Erc20Abi.smartContractMethodIdentifier("decimals()")
        );
    }

    private BigInteger tokenTotalSupply() {
        Bytes runtimeCode = worldState.getCode(CONTRACT_ADDRESS);
        return EvmService.callUint(
                worldState,
                treasury,
                CONTRACT_ADDRESS,
                runtimeCode,
                "0x" + Erc20Abi.smartContractMethodIdentifier("totalSupply()")
        );
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}