package ist.depchain.tests.stage2.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.tests.stage2.GasConstants;

/**
 * Integration tests for balance reads that go through consensus.
 *
 * Reads are treated as normal transactions: the client submits a balance query
 * transaction that goes through HotStuff consensus, gets included in a block,
 * and the result is returned via the commit response. Because they are real
 * transactions, reads consume a nonce and pay gas fees.
 *
 * Tests:
 * 1. Read native balance at genesis (before any transfer).
 * 2. Read ERC-20 token balance at genesis.
 * 3. Read native balance after a committed transfer reflects the new state.
 * 4. Read ERC-20 balance after a committed transfer reflects the new state.
 * 5. Two sequential reads return consistent results.
 * 6. Read consumes a nonce (it is a real transaction).
 * 7. Read after two cumulative transfers returns the correct final balance.
 * 8. Both clients can independently read their own balances after a transfer.
 * 9. Receiver reads updated balance after incoming transfer.
 * 10. State hashes match across all replicas after a read.
 */
class ReadTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };

    private static final BigInteger GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger GAS_FEE = GAS_PRICE.multiply(GasConstants.NATIVE_TRANSFER_GAS_COST);

    private static final BigInteger ERC20_GAS_PRICE = BigInteger.ONE;
    private static final BigInteger ERC20_GAS_LIMIT = BigInteger.valueOf(100_000);

    // Query gas: ClientLibrary uses gasPrice=1, gasLimit=100_000; actual gas used = NATIVE_TRANSFER_GAS
    private static final BigInteger QUERY_GAS_FEE = BigInteger.ONE.multiply(GasConstants.NATIVE_TRANSFER_GAS_COST);

    private static final BigInteger ADDED_NATIVE_BALANCE = BigInteger.valueOf(10_000_000);

    private ClientContext client1Context;
    private ClientContext client2Context;
    private ClientLibrary client1Library;
    private ClientLibrary client2Library;

    // Actual balances after setup (genesis + added), read from world state
    private BigInteger client1NativeBalance;
    private BigInteger client2NativeBalance;
    private BigInteger client1TokenBalance;
    private BigInteger client2TokenBalance;

    @BeforeEach
    void setup() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config client1Config = Config.loadConfiguration(CONFIG_FILE, "client1");
        Config client2Config = Config.loadConfiguration(CONFIG_FILE, "client2");

        client1Context = new ClientContext(client1Config);
        client2Context = new ClientContext(client2Config);
        MessageHandler client1Handler = new MessageHandler(client1Context);
        MessageHandler client2Handler = new MessageHandler(client2Context);
        client1Library = new ClientLibrary(client1Context, client1Handler);
        client2Library = new ClientLibrary(client2Context, client2Handler);

        Address client1Address = client1Context.getSelfAddress();
        Address client2Address = client2Context.getSelfAddress();

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord);

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(client1Address)) {
                ws.createEOA(client1Address, 0, ADDED_NATIVE_BALANCE);
            } else {
                ws.addBalance(client1Address, ADDED_NATIVE_BALANCE);
            }

            if (!ws.accountExists(client2Address)) {
                ws.createEOA(client2Address, 0, ADDED_NATIVE_BALANCE);
            } else {
                ws.addBalance(client2Address, ADDED_NATIVE_BALANCE);
            }
        }

        // Read actual balances after genesis + seeding
        DepChainWorldState refState = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        client1NativeBalance = refState.getBalance(client1Address);
        client2NativeBalance = refState.getBalance(client2Address);

        Address contractAddress = ServerApp.getCoordinator("s0").getServerContext().getConfig().getIstContractAddress();
        client1TokenBalance = tokenBalanceOf(refState, contractAddress, client1Address);
        client2TokenBalance = tokenBalanceOf(refState, contractAddress, client2Address);

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        client1Context.setRequestId((int) requestBase);
        client2Context.setRequestId((int) (requestBase + 500));

        client1Context.setNonce(
                ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client1Address));
        client2Context.setNonce(
                ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client2Address));

        client1Context.start();
        client2Context.start();
    }

    @AfterEach
    void teardown() {
        if (client1Context != null)
            client1Context.stop();
        if (client2Context != null)
            client2Context.stop();
        stopReplicas();
    }

    // 1. Genesis reads

    /**
     * Read native balance before any transfer. The consensus-based read
     * should succeed without throwing.
     */
    @Test
    void readNativeBalanceAtGenesis() {
        assertDoesNotThrow(() -> client1Library.submitNativeBalanceCheck(),
                "native balance read at genesis should succeed through consensus");
    }

    /**
     * Read ERC-20 token balance before any transfer.
     * Should succeed through consensus without throwing.
     */
    @Test
    void readTokenBalanceAtGenesis() {
        assertDoesNotThrow(() -> client1Library.submitTokenBalanceCheck(),
                "ERC-20 balance read at genesis should succeed through consensus");
    }

    // 2. Reads after writes

    /**
     * After a native transfer is committed, a balance read through consensus
     * should reflect the deducted amount and gas fee on all replicas.
     */
    @Test
    void readNativeBalanceAfterTransfer() {
        Address sender = client1Context.getSelfAddress();
        Address receiver = client2Context.getSelfAddress();
        BigInteger transferValue = BigInteger.valueOf(5_000);

        client1Library.submitNativeTransfer(receiver.toHexString(), transferValue, GAS_PRICE, GAS_LIMIT);

        BigInteger expectedBalance = client1NativeBalance.subtract(transferValue).subtract(GAS_FEE);
        waitForNativeBalance(sender, expectedBalance, 120_000);

        // Submit a balance read (also a consensus transaction, costs gas)
        client1Library.submitNativeBalanceCheck();

        BigInteger expectedAfterRead = expectedBalance.subtract(QUERY_GAS_FEE);
        waitForNativeBalance(sender, expectedAfterRead, 120_000);

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedAfterRead, ws.getBalance(sender),
                    "sender balance after transfer + read should match on " + replicaId);
        }
    }

    /**
     * After an ERC-20 transfer is committed, a token balance read through
     * consensus should succeed and all replicas should agree on the token balance.
     */
    @Test
    void readTokenBalanceAfterTransfer() {
        Address sender = client1Context.getSelfAddress();
        Address receiver = client2Context.getSelfAddress();
        BigInteger transferAmount = BigInteger.valueOf(3_000);

        client1Library.submitTokenTransfer(receiver.toHexString(), transferAmount, ERC20_GAS_PRICE, ERC20_GAS_LIMIT);

        BigInteger expectedTokenBalance = client1TokenBalance.subtract(transferAmount);
        waitForTokenBalance(sender, expectedTokenBalance, 120_000);

        // Submit a token balance read through consensus
        assertDoesNotThrow(() -> client1Library.submitTokenBalanceCheck(),
                "token balance read after transfer should succeed through consensus");

        // Verify all replicas agree on the token balance
        Address contractAddress = ServerApp.getCoordinator("s0").getServerContext().getConfig().getIstContractAddress();
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            Address contract = ServerApp.getCoordinator(replicaId).getServerContext().getConfig()
                    .getIstContractAddress();
            assertEquals(expectedTokenBalance, tokenBalanceOf(ws, contract, sender),
                    "ERC-20 balance should match on " + replicaId);
        }
    }

    // 3. Consistency / nonce behavior

    /**
     * Two consecutive reads without any writes should both succeed through
     * consensus and produce consistent state across replicas.
     */
    @Test
    void consecutiveReadsReturnConsistentState() {
        client1Library.submitNativeBalanceCheck();
        client1Library.submitNativeBalanceCheck();

        // After two reads, all replicas should have the same state
        assertReplicaStateHashesMatch();
    }

    /**
     * A consensus-based read IS a real transaction and MUST advance the
     * client's nonce. Two balance reads should consume two nonces.
     */
    @Test
    void readConsumesNonce() {
        long nonceBefore = client1Context.getNonce();

        client1Library.submitNativeBalanceCheck();
        client1Library.submitTokenBalanceCheck();

        long nonceAfter = client1Context.getNonce();
        assertEquals(nonceBefore + 2, nonceAfter,
                "each read transaction should consume one nonce");
    }

    // 4. Cumulative transfers

    /**
     * After two sequential transfers, a read should succeed and all replicas
     * should reflect the cumulative effect.
     */
    @Test
    void readAfterMultipleTransfersReflectsCumulativeBalance() {
        Address sender = client1Context.getSelfAddress();
        Address receiver = client2Context.getSelfAddress();
        BigInteger transfer1 = BigInteger.valueOf(1_000);
        BigInteger transfer2 = BigInteger.valueOf(2_000);

        client1Library.submitNativeTransfer(receiver.toHexString(), transfer1, GAS_PRICE, GAS_LIMIT);
        client1Library.submitNativeTransfer(receiver.toHexString(), transfer2, GAS_PRICE, GAS_LIMIT);

        BigInteger totalTransferred = transfer1.add(transfer2);
        BigInteger totalFees = GAS_FEE.multiply(BigInteger.TWO);
        BigInteger expectedSender = client1NativeBalance.subtract(totalTransferred).subtract(totalFees);

        waitForNativeBalance(sender, expectedSender, 120_000);

        // Submit a balance read through consensus
        client1Library.submitNativeBalanceCheck();

        BigInteger expectedAfterRead = expectedSender.subtract(QUERY_GAS_FEE);
        waitForNativeBalance(sender, expectedAfterRead, 120_000);

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedAfterRead, ws.getBalance(sender),
                    "balance after two transfers + read should be consistent on " + replicaId);
        }
    }

    // 5. Multi-client reads

    /**
     * After a transfer from client1 to client2, both clients should be able to
     * independently read their balances via consensus-based queries and all
     * replicas should agree on the final state.
     */
    @Test
    void bothClientsReadCorrectBalancesAfterTransfer() {
        Address client1Addr = client1Context.getSelfAddress();
        Address client2Addr = client2Context.getSelfAddress();
        BigInteger transferValue = BigInteger.valueOf(5_000);

        client1Library.submitNativeTransfer(client2Addr.toHexString(), transferValue, GAS_PRICE, GAS_LIMIT);

        BigInteger expectedClient1 = client1NativeBalance.subtract(transferValue).subtract(GAS_FEE);
        BigInteger expectedClient2 = client2NativeBalance.add(transferValue);

        waitForNativeBalance(client1Addr, expectedClient1, 120_000);

        // Both clients submit balance reads through consensus
        client1Library.submitNativeBalanceCheck();
        client2Library.submitNativeBalanceCheck();

        BigInteger expectedClient1AfterRead = expectedClient1.subtract(QUERY_GAS_FEE);
        BigInteger expectedClient2AfterRead = expectedClient2.subtract(QUERY_GAS_FEE);

        waitForNativeBalance(client1Addr, expectedClient1AfterRead, 120_000);
        waitForNativeBalance(client2Addr, expectedClient2AfterRead, 120_000);

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedClient1AfterRead, ws.getBalance(client1Addr),
                    "client1 balance should be correct on " + replicaId);
            assertEquals(expectedClient2AfterRead, ws.getBalance(client2Addr),
                    "client2 balance should be correct on " + replicaId);
        }

        assertReplicaStateHashesMatch();
    }

    /**
     * A transfer followed by a read from the receiver should succeed.
     * client1 transfers native coins, client2 submits a balance read.
     */
    @Test
    void receiverReadsUpdatedBalanceAfterIncomingTransfer() {
        Address client2Addr = client2Context.getSelfAddress();
        BigInteger transferValue = BigInteger.valueOf(7_777);

        client1Library.submitNativeTransfer(client2Addr.toHexString(), transferValue, GAS_PRICE, GAS_LIMIT);

        BigInteger expectedClient2 = client2NativeBalance.add(transferValue);
        waitForNativeBalance(client2Addr, expectedClient2, 120_000);

        // Receiver submits a balance read through consensus
        client2Library.submitNativeBalanceCheck();

        BigInteger expectedClient2AfterRead = expectedClient2.subtract(QUERY_GAS_FEE);
        waitForNativeBalance(client2Addr, expectedClient2AfterRead, 120_000);

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedClient2AfterRead, ws.getBalance(client2Addr),
                    "receiver balance after transfer + read should match on " + replicaId);
        }
    }

    // 6. State hash consistency

    /**
     * After a consensus-based read, all replica state hashes should match.
     */
    @Test
    void readStateHashMatchesAllReplicaStateHashes() {
        client1Library.submitNativeBalanceCheck();

        // Wait for the read transaction to be committed on all replicas
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));

        assertReplicaStateHashesMatch();
    }

    // ==================== Helpers ====================

    private void waitForNativeBalance(Address address, BigInteger expectedBalance, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
                if (!ws.getBalance(address).equals(expectedBalance)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch)
                return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        BigInteger actual = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(address);
        fail("Balance did not converge within " + timeoutMs + "ms; expected=" + expectedBalance + " actual=" + actual);
    }

    private void waitForTokenBalance(Address address, BigInteger expectedBalance, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
                Address contractAddress = ServerApp.getCoordinator(replicaId).getServerContext().getConfig()
                        .getIstContractAddress();
                if (!tokenBalanceOf(ws, contractAddress, address).equals(expectedBalance)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch)
                return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        fail("Token balance did not converge within " + timeoutMs + "ms");
    }

    private static BigInteger tokenBalanceOf(DepChainWorldState ws, Address contractAddress, Address owner) {
        org.apache.tuweni.units.bigints.UInt256 slot = org.apache.tuweni.units.bigints.UInt256.fromBytes(
                org.apache.tuweni.bytes.Bytes
                        .wrap(org.web3j.crypto.Hash.sha3(concat(padAddress(owner), padWord(BigInteger.ZERO)))));
        return ws.getStorageValue(contractAddress, slot).toBigInteger();
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
}
