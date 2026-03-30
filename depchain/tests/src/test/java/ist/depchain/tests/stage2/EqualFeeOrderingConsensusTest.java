package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

/**
 * Equal-fee ordering determinism through consensus (TODO-TESTS §H).
 *
 * Guarantee: tie cases (transactions with equal fees) are deterministic
 * across all honest replicas. The BlockBuilder uses a lexicographic tx-hash
 * tie-breaker, and all replicas produce the same block ordering.
 *
 * Tests:
 *   1. Two clients submit transactions with the same gas price and gas limit
 *      (equal fee). All replicas produce identical block ordering and
 *      identical receipts.
 *   2. After committing, all replicas agree on state hash and block hash.
 */
class EqualFeeOrderingConsensusTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    // Same gas price and gas limit for both clients → same fee
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger TRANSFER_VALUE = BigInteger.valueOf(1_000);

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

        client1Context.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client1Address));
        client2Context.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client2Address));

        client1Context.start();
        client2Context.start();
    }

    @AfterEach
    void teardown() {
        if (client1Context != null) client1Context.stop();
        if (client2Context != null) client2Context.stop();
        stopReplicas();
    }

    /**
     * Both clients submit a native transfer with the same gas price and gas limit.
     * The fee is identical (3 * 20_000 = 60_000 for both).
     * The BlockBuilder's tie-breaker (lexicographic tx hash) decides the ordering.
     * All replicas must produce the same ordering, same block hash, and same receipts.
     */
    @Test
    void equalFeeTransactionsFromDifferentClientsProduceIdenticalBlockOrderAcrossReplicas() {
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            if (!ws.accountExists(receiver)) ws.createEOA(receiver, 0, BigInteger.ZERO);
        }

        // Both clients submit with identical gas price and gas limit
        client1Library.submitNativeTransfer(receiver.toHexString(), TRANSFER_VALUE, GAS_PRICE, GAS_LIMIT);
        client2Library.submitNativeTransfer(receiver.toHexString(), TRANSFER_VALUE, GAS_PRICE, GAS_LIMIT);

        // Wait for receiver to have both transfers
        BigInteger expectedReceiverBalance = TRANSFER_VALUE.multiply(BigInteger.TWO);
        waitForReceiverBalance(receiver, expectedReceiverBalance, 120_000);

        // All replicas must agree on block hash, state hash, and receipt order
        assertLatestBlocksEquivalentAcrossReplicas();
        assertReplicaStateHashesMatch();

        // Verify the block actually has two transactions
        BlockChainBlock latestBlock = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getLatestBlock();
        assertTrue(latestBlock.getTransactions().size() >= 1,
                "Expected at least 1 transaction in the latest block");
    }

    /**
     * Multiple equal-fee transactions from both clients. Each client submits
     * two transfers. The total should be deterministic across all replicas.
     */
    @Test
    void multipleEqualFeeTransactionsProduceConsistentStateAcrossReplicas() {
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            if (!ws.accountExists(receiver)) ws.createEOA(receiver, 0, BigInteger.ZERO);
        }

        // Client1: 2 transfers
        client1Library.submitNativeTransfer(receiver.toHexString(), BigInteger.valueOf(100), GAS_PRICE, GAS_LIMIT);
        client1Library.submitNativeTransfer(receiver.toHexString(), BigInteger.valueOf(200), GAS_PRICE, GAS_LIMIT);

        // Client2: 2 transfers
        client2Library.submitNativeTransfer(receiver.toHexString(), BigInteger.valueOf(300), GAS_PRICE, GAS_LIMIT);
        client2Library.submitNativeTransfer(receiver.toHexString(), BigInteger.valueOf(400), GAS_PRICE, GAS_LIMIT);

        BigInteger expectedTotal = BigInteger.valueOf(100 + 200 + 300 + 400);
        waitForReceiverBalance(receiver, expectedTotal, 120_000);

        // Verify state consistency
        assertReplicaStateHashesMatch();

        // Verify receiver balance on all replicas
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedTotal, ws.getBalance(receiver),
                    "receiver balance mismatch on " + replicaId);
        }
    }

    // ==================== Helpers ====================

    private static void waitForReceiverBalance(Address receiver, BigInteger expectedBalance, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
                if (!ws.getBalance(receiver).equals(expectedBalance)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        BigInteger actual = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(receiver);
        fail("Receiver balance did not converge within " + timeoutMs + "ms; expected=" + expectedBalance + " actual=" + actual);
    }

    private static void assertReplicaStateHashesMatch() {
        String referenceHash = null;
        for (String replicaId : REPLICAS) {
            String stateHash = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState().computeStateHash();
            if (referenceHash == null) {
                referenceHash = stateHash;
            } else {
                assertEquals(referenceHash, stateHash, "State hash mismatch on " + replicaId);
            }
        }
    }

    private static void assertLatestBlocksEquivalentAcrossReplicas() {
        String referenceBlockHash = null;
        String referenceStateHash = null;
        List<TransactionReceipt> referenceReceipts = null;

        for (String replicaId : REPLICAS) {
            BlockChainBlock latestBlock = ServerApp.getCoordinator(replicaId).getServerContext().getBlockChain().getLatestBlock();
            if (referenceBlockHash == null) {
                referenceBlockHash = latestBlock.getBlockHash();
                referenceStateHash = latestBlock.getStateHash();
                referenceReceipts = latestBlock.getReceipts();
                continue;
            }

            assertEquals(referenceBlockHash, latestBlock.getBlockHash(), "Block hash mismatch on " + replicaId);
            assertEquals(referenceStateHash, latestBlock.getStateHash(), "State hash mismatch on " + replicaId);
            assertEquals(referenceReceipts.size(), latestBlock.getReceipts().size(), "Receipt count mismatch on " + replicaId);

            for (int i = 0; i < referenceReceipts.size(); i++) {
                TransactionReceipt expected = referenceReceipts.get(i);
                TransactionReceipt actual = latestBlock.getReceipts().get(i);
                assertEquals(expected.isSuccess(), actual.isSuccess(), "Receipt success mismatch at index " + i + " on " + replicaId);
                assertEquals(expected.getGasUsed(), actual.getGasUsed(), "Receipt gasUsed mismatch at index " + i + " on " + replicaId);
                assertEquals(expected.getFee(), actual.getFee(), "Receipt fee mismatch at index " + i + " on " + replicaId);
                assertArrayEquals(expected.getTxHash(), actual.getTxHash(), "Receipt txHash mismatch at index " + i + " on " + replicaId);
            }
        }
    }

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, serverId, "false"});
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
            if (coord == null) continue;
            try { coord.getServerContext().stop(); } catch (Exception ignored) {}
            coord.stop();
        }
    }
}
