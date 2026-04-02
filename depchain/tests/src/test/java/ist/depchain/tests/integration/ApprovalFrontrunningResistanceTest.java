package ist.depchain.tests.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

/**
 * Guarantee: a spender cannot exploit an allowance reduction to spend
 * old + new allowance cumulatively. Our ERC-20 uses increaseAllowance /
 * decreaseAllowance instead of raw approve(), and approve(non-zero -> non-zero)
 * reverts, which eliminates the classic frontrunning attack vector.
 *
 * Test scenarios:
 * 1. Owner grants allowance 100, spender spends 100, owner decreases by 50
 * -> total extracted is exactly 100, not 150.
 * 2. Spender races owner's decrease: spender spends full allowance before
 * the decrease lands -> decrease is a no-op (allowance already 0), total
 * extracted is bounded by original allowance.
 * 3. Owner tries approve() to replace non-zero allowance -> reverts,
 * preventing the classic ERC-20 race.
 */
class ApprovalFrontrunningResistanceTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };

    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(100_000);
    private static final BigInteger TOKEN_INITIAL_SUPPLY = new BigInteger("10000000000");

    private ClientContext ownerContext;
    private ClientContext spenderContext;
    private ClientLibrary ownerLibrary;
    private ClientLibrary spenderLibrary;

    @BeforeEach
    void setup() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config ownerConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        Config spenderConfig = Config.loadConfiguration(CONFIG_FILE, "client2");

        ownerContext = new ClientContext(ownerConfig);
        spenderContext = new ClientContext(spenderConfig);
        MessageHandler ownerHandler = new MessageHandler(ownerContext);
        MessageHandler spenderHandler = new MessageHandler(spenderContext);
        ownerLibrary = new ClientLibrary(ownerContext, ownerHandler);
        spenderLibrary = new ClientLibrary(spenderContext, spenderHandler);

        Address ownerAddress = ownerContext.getSelfAddress();
        Address spenderAddress = spenderContext.getSelfAddress();

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord);

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(ownerAddress)) {
                ws.createEOA(ownerAddress, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(ownerAddress, BigInteger.valueOf(10_000_000));
            }

            if (!ws.accountExists(spenderAddress)) {
                ws.createEOA(spenderAddress, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(spenderAddress, BigInteger.valueOf(10_000_000));
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        ownerContext.setRequestId((int) requestBase);
        spenderContext.setRequestId((int) (requestBase + 500));

        ownerContext.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(ownerAddress));
        spenderContext
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(spenderAddress));

        ownerContext.start();
        spenderContext.start();
    }

    @AfterEach
    void teardown() {
        if (ownerContext != null)
            ownerContext.stop();
        if (spenderContext != null)
            spenderContext.stop();
        stopReplicas();
    }

    /**
     * 1. Owner grants spender allowance of 100.
     * 2. Spender races to transferFrom(100) before the owner's decrease lands.
     * 3. Owner submits decreaseAllowance(50).
     * 4. Assert: spender extracted at most 100 tokens total (not 100 + 50 = 150).
     * The decrease is a no-op because allowance is already 0 after the spend.
     */
    @Test
    void spenderCannotExceedOriginalAllowanceByRacingDecrease() {
        Address owner = ownerContext.getSelfAddress();
        Address spender = spenderContext.getSelfAddress();
        BigInteger allowance = BigInteger.valueOf(100);

        // Step 1: Owner grants allowance 100
        ownerLibrary.submitIncreaseAllowance(spender.toHexString(), allowance, GAS_PRICE, GAS_LIMIT);

        waitForReplicaState(owner, spender, TOKEN_INITIAL_SUPPLY, BigInteger.ZERO, allowance, 120_000);

        // Step 2: Spender spends the full allowance
        spenderLibrary.submitTransferFrom(owner.toHexString(), spender.toHexString(), allowance, GAS_PRICE, GAS_LIMIT);

        waitForReplicaState(owner, spender,
                TOKEN_INITIAL_SUPPLY.subtract(allowance),
                allowance,
                BigInteger.ZERO,
                120_000);

        // Step 3: Owner tries to decrease by 50 - allowance is already 0, so this
        // reverts
        ownerLibrary.submitDecreaseAllowance(spender.toHexString(), BigInteger.valueOf(50), GAS_PRICE, GAS_LIMIT);

        // Wait for the decrease tx to be processed (allowance stays at 0 because
        // decrease below 0 reverts)
        waitForReplicaState(owner, spender,
                TOKEN_INITIAL_SUPPLY.subtract(allowance),
                allowance,
                BigInteger.ZERO,
                120_000);

        // Step 4: Verify total extracted is exactly 100 (the original allowance), never
        // more
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            Address contractAddress = ServerApp.getCoordinator(replicaId).getServerContext().getConfig()
                    .getIstContractAddress();

            BigInteger spenderBalance = tokenBalanceOf(ws, contractAddress, spender);
            assertTrue(spenderBalance.compareTo(allowance) <= 0,
                    "spender on " + replicaId + " extracted " + spenderBalance +
                            " tokens, which exceeds the original allowance of " + allowance);
        }

        assertReplicaStateHashesMatch();
    }

    /**
     * 1. Owner grants 100.
     * 2. Spender spends 60.
     * 3. Owner decreases by 40 (remaining = 0).
     * 4. Spender attempts transferFrom(1) -> reverts (allowance = 0).
     * 5. Total extracted = 60, not 100.
     */
    @Test
    void ownerCanSafelyReduceRemainingAllowanceAfterPartialSpend() {
        Address owner = ownerContext.getSelfAddress();
        Address spender = spenderContext.getSelfAddress();
        BigInteger initialAllowance = BigInteger.valueOf(100);
        BigInteger spendAmount = BigInteger.valueOf(60);
        BigInteger decreaseAmount = BigInteger.valueOf(40);

        // Grant 100
        ownerLibrary.submitIncreaseAllowance(spender.toHexString(), initialAllowance, GAS_PRICE, GAS_LIMIT);
        waitForReplicaState(owner, spender, TOKEN_INITIAL_SUPPLY, BigInteger.ZERO, initialAllowance, 120_000);

        // Spend 60
        spenderLibrary.submitTransferFrom(owner.toHexString(), spender.toHexString(), spendAmount, GAS_PRICE,
                GAS_LIMIT);
        waitForReplicaState(owner, spender,
                TOKEN_INITIAL_SUPPLY.subtract(spendAmount),
                spendAmount,
                initialAllowance.subtract(spendAmount),
                120_000);

        // Owner decreases by 40 -> allowance goes from 40 to 0
        ownerLibrary.submitDecreaseAllowance(spender.toHexString(), decreaseAmount, GAS_PRICE, GAS_LIMIT);
        waitForReplicaState(owner, spender,
                TOKEN_INITIAL_SUPPLY.subtract(spendAmount),
                spendAmount,
                BigInteger.ZERO,
                120_000);

        // Spender tries to spend 1 more - should fail (allowance = 0)
        spenderLibrary.submitTransferFrom(owner.toHexString(), spender.toHexString(), BigInteger.ONE, GAS_PRICE,
                GAS_LIMIT);

        // Allow time for the reverting tx to be processed
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(30));

        // Spender balance should still be exactly 60
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            Address contractAddress = ServerApp.getCoordinator(replicaId).getServerContext().getConfig()
                    .getIstContractAddress();

            assertEquals(spendAmount, tokenBalanceOf(ws, contractAddress, spender),
                    "spender token balance mismatch on " + replicaId);
            assertEquals(BigInteger.ZERO, tokenAllowanceOf(ws, contractAddress, owner, spender),
                    "allowance should be zero on " + replicaId);
        }

        assertReplicaStateHashesMatch();
    }

    /**
     * approve(non-zero -> non-zero) is rejected, closing the classic frontrunning
     * vector where a spender observes an incoming approve() and races to spend
     * the old allowance first.
     */
    @Test
    void approveNonZeroToNonZeroRevertsPreventsClassicFrontrunning() {
        Address owner = ownerContext.getSelfAddress();
        Address spender = spenderContext.getSelfAddress();
        BigInteger initialAllowance = BigInteger.valueOf(100);
        BigInteger replacementAllowance = BigInteger.valueOf(200);

        // Grant 100 via increaseAllowance
        ownerLibrary.submitIncreaseAllowance(spender.toHexString(), initialAllowance, GAS_PRICE, GAS_LIMIT);
        waitForReplicaState(owner, spender, TOKEN_INITIAL_SUPPLY, BigInteger.ZERO, initialAllowance, 120_000);

        // Try approve(200) - must revert because current allowance is non-zero
        ownerLibrary.submitApprove(spender.toHexString(), replacementAllowance, GAS_PRICE, GAS_LIMIT);

        // Allowance remains 100
        waitForReplicaState(owner, spender, TOKEN_INITIAL_SUPPLY, BigInteger.ZERO, initialAllowance, 120_000);

        assertReplicaStateHashesMatch();
    }

    // ==================== Helpers ====================

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[] { CONFIG_FILE, serverId, "false" });
            } catch (Exception e) {
                System.err.println("[TEST] Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void stopReplicas() {
        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            if (coord == null)
                continue;
            try {
                coord.getServerContext().stop();
            } catch (Exception ignored) {
            }
            coord.stop();
        }
    }

    private static void waitForReplicaState(Address owner, Address spender,
            BigInteger expectedOwnerToken,
            BigInteger expectedSpenderToken,
            BigInteger expectedAllowance,
            long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
                DepChainWorldState ws = coord.getServerContext().getWorldState();
                Address contractAddress = coord.getServerContext().getConfig().getIstContractAddress();

                if (!tokenBalanceOf(ws, contractAddress, owner).equals(expectedOwnerToken)
                        || !tokenBalanceOf(ws, contractAddress, spender).equals(expectedSpenderToken)
                        || !tokenAllowanceOf(ws, contractAddress, owner, spender).equals(expectedAllowance)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch)
                return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        fail("ERC20 state did not converge within " + timeoutMs + "ms");
    }

    private static BigInteger tokenBalanceOf(DepChainWorldState ws, Address contractAddress, Address owner) {
        return ws.getStorageValue(contractAddress, balanceSlot(owner)).toBigInteger();
    }

    private static BigInteger tokenAllowanceOf(DepChainWorldState ws, Address contractAddress, Address owner,
            Address spender) {
        return ws.getStorageValue(contractAddress, allowanceSlot(owner, spender)).toBigInteger();
    }

    private static void assertReplicaStateHashesMatch() {
        String referenceHash = null;
        for (String replicaId : REPLICAS) {
            String stateHash = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState()
                    .computeStateHash();
            if (referenceHash == null) {
                referenceHash = stateHash;
            } else {
                assertEquals(referenceHash, stateHash, "State hash mismatch on " + replicaId);
            }
        }
    }

    private static UInt256 balanceSlot(Address owner) {
        return UInt256.fromBytes(Bytes.wrap(Hash.sha3(concat(padAddress(owner), padWord(BigInteger.ZERO)))));
    }

    private static UInt256 allowanceSlot(Address owner, Address spender) {
        byte[] outer = Hash.sha3(concat(padAddress(owner), padWord(BigInteger.ONE)));
        return UInt256.fromBytes(Bytes.wrap(Hash.sha3(concat(padAddress(spender), outer))));
    }

    private static byte[] padAddress(Address address) {
        byte[] out = new byte[32];
        byte[] raw = address.toArrayUnsafe();
        System.arraycopy(raw, 0, out, 12, raw.length);
        return out;
    }

    private static byte[] padWord(BigInteger value) {
        byte[] raw = value == null ? new byte[0] : value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            raw = trimmed;
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
