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
 * Guarantee: reads are quorum-based snapshots - all honest replicas return
 * consistent balances after transactions are committed. A balance query
 * submitted after a transfer should reflect the transfer on all replicas.
 *
 * Tests:
 * 1. Native balance query after a transfer returns the updated balance
 * on all replicas.
 * 2. Token balance query after an ERC-20 transfer returns the updated
 * balance on all replicas.
 * 3. Sequential transfers followed by balance queries produce consistent
 * results across replicas.
 */
class QueryConsistencyTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };

    private static final BigInteger GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger GAS_FEE = GAS_PRICE.multiply(GasConstants.NATIVE_TRANSFER_GAS_COST);
    private static final BigInteger TRANSFER_VALUE = BigInteger.valueOf(5_000);

    private static final BigInteger ERC20_GAS_PRICE = BigInteger.ONE;
    private static final BigInteger ERC20_GAS_LIMIT = BigInteger.valueOf(100_000);

    private ClientContext client1Context;
    private ClientContext client2Context;
    private ClientLibrary client1Library;
    private ClientLibrary client2Library;

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
                ws.createEOA(client1Address, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(client1Address, BigInteger.valueOf(10_000_000));
            }

            if (!ws.accountExists(client2Address)) {
                ws.createEOA(client2Address, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(client2Address, BigInteger.valueOf(10_000_000));
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        client1Context.setRequestId((int) requestBase);
        client2Context.setRequestId((int) (requestBase + 500));

        client1Context
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client1Address));
        client2Context
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client2Address));

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

    /**
     * After a native transfer from client1 to client2, all replicas must
     * report the same updated balance for both accounts.
     */
    @Test
    void nativeBalanceConsistentAcrossReplicasAfterTransfer() {
        Address sender = client1Context.getSelfAddress();
        Address receiver = client2Context.getSelfAddress();

        BigInteger senderBefore = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(sender);
        BigInteger receiverBefore = ServerApp.getCoordinator("s0").getServerContext().getWorldState()
                .getBalance(receiver);

        client1Library.submitNativeTransfer(receiver.toHexString(), TRANSFER_VALUE, GAS_PRICE, GAS_LIMIT);

        BigInteger expectedSender = senderBefore.subtract(TRANSFER_VALUE).subtract(GAS_FEE);
        BigInteger expectedReceiver = receiverBefore.add(TRANSFER_VALUE);

        waitForNativeBalance(sender, expectedSender, 120_000);

        // Verify all replicas agree
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedSender, ws.getBalance(sender),
                    "sender balance mismatch on " + replicaId);
            assertEquals(expectedReceiver, ws.getBalance(receiver),
                    "receiver balance mismatch on " + replicaId);
        }

        assertReplicaStateHashesMatch();
    }

    /**
     * After an ERC-20 token transfer, all replicas must report the same
     * token balances.
     */
    @Test
    void tokenBalanceConsistentAcrossReplicasAfterTransfer() {
        Address sender = client1Context.getSelfAddress();
        Address receiver = client2Context.getSelfAddress();
        BigInteger transferAmount = BigInteger.valueOf(3_000);

        client1Library.submitTokenTransfer(receiver.toHexString(), transferAmount, ERC20_GAS_PRICE, ERC20_GAS_LIMIT);

        // Wait for token balances to converge
        BigInteger initialSupply = new BigInteger("10000000000");
        BigInteger expectedSenderToken = initialSupply.subtract(transferAmount);
        BigInteger expectedReceiverToken = transferAmount;

        waitForTokenBalance(sender, expectedSenderToken, 120_000);

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            Address contractAddress = ServerApp.getCoordinator(replicaId).getServerContext().getConfig()
                    .getIstContractAddress();

            assertEquals(expectedSenderToken, tokenBalanceOf(ws, contractAddress, sender),
                    "sender token balance mismatch on " + replicaId);
            assertEquals(expectedReceiverToken, tokenBalanceOf(ws, contractAddress, receiver),
                    "receiver token balance mismatch on " + replicaId);
        }

        assertReplicaStateHashesMatch();
    }

    /**
     * Two sequential native transfers, then verify both replicas see the
     * cumulative effect. This tests that reads after multiple writes are
     * consistent.
     */
    @Test
    void multipleTransfersProduceConsistentCumulativeBalances() {
        Address sender = client1Context.getSelfAddress();
        Address receiver = client2Context.getSelfAddress();

        BigInteger senderBefore = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(sender);
        BigInteger receiverBefore = ServerApp.getCoordinator("s0").getServerContext().getWorldState()
                .getBalance(receiver);

        BigInteger transfer1 = BigInteger.valueOf(1_000);
        BigInteger transfer2 = BigInteger.valueOf(2_000);

        client1Library.submitNativeTransfer(receiver.toHexString(), transfer1, GAS_PRICE, GAS_LIMIT);
        client1Library.submitNativeTransfer(receiver.toHexString(), transfer2, GAS_PRICE, GAS_LIMIT);

        BigInteger totalTransferred = transfer1.add(transfer2);
        BigInteger totalFees = GAS_FEE.multiply(BigInteger.TWO);
        BigInteger expectedSender = senderBefore.subtract(totalTransferred).subtract(totalFees);
        BigInteger expectedReceiver = receiverBefore.add(totalTransferred);

        waitForNativeBalance(sender, expectedSender, 120_000);

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedSender, ws.getBalance(sender),
                    "cumulative sender balance mismatch on " + replicaId);
            assertEquals(expectedReceiver, ws.getBalance(receiver),
                    "cumulative receiver balance mismatch on " + replicaId);
        }

        assertReplicaStateHashesMatch();
    }

    /**
     * Both clients transfer native coins to each other simultaneously.
     * After both commit, all replicas must agree on the final balances.
     */
    @Test
    void bidirectionalTransfersProduceConsistentState() {
        Address client1 = client1Context.getSelfAddress();
        Address client2 = client2Context.getSelfAddress();

        BigInteger client1Before = ServerApp.getCoordinator("s0").getServerContext().getWorldState()
                .getBalance(client1);
        BigInteger client2Before = ServerApp.getCoordinator("s0").getServerContext().getWorldState()
                .getBalance(client2);

        BigInteger amount1to2 = BigInteger.valueOf(3_000);
        BigInteger amount2to1 = BigInteger.valueOf(1_500);

        client1Library.submitNativeTransfer(client2.toHexString(), amount1to2, GAS_PRICE, GAS_LIMIT);
        client2Library.submitNativeTransfer(client1.toHexString(), amount2to1, GAS_PRICE, GAS_LIMIT);

        // Net effect: client1 sends 3000, receives 1500, pays gas; client2 sends 1500,
        // receives 3000, pays gas
        BigInteger expectedClient1 = client1Before.subtract(amount1to2).add(amount2to1).subtract(GAS_FEE);
        BigInteger expectedClient2 = client2Before.subtract(amount2to1).add(amount1to2).subtract(GAS_FEE);

        // Wait for convergence
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
                if (!ws.getBalance(client1).equals(expectedClient1)
                        || !ws.getBalance(client2).equals(expectedClient2)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch)
                break;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedClient1, ws.getBalance(client1),
                    "client1 balance mismatch on " + replicaId);
            assertEquals(expectedClient2, ws.getBalance(client2),
                    "client2 balance mismatch on " + replicaId);
        }

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
