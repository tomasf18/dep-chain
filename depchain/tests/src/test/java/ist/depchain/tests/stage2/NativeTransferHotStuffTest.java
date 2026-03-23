package ist.depchain.tests.stage2;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: submits a native DepCoin transfer through the full
 * BasicHotStuff consensus (4 replicas, 4 HotStuff phases) and verifies
 * that the client receives f+1 matching responses.
 *
 * This test exercises the complete Stage 2 pipeline end-to-end:
 *   Client → MessageHandler (validate tx) → Mempool → doPropose (batch)
 *   → PREPARE → PRE-COMMIT → COMMIT → DECIDE → executeStage2Block
 *   → TransactionExecutor → BlockChain persist → ClientResponse
 */
public class NativeTransferHotStuffTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    private static final String RECEIVER_HEX = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws Exception {
        System.out.println("[TEST] Starting NativeTransferHotStuffTest");

        // 1. Start 4 honest replicas
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        // 2. Wait for handshake + BLS init
        System.out.println("[TEST] Waiting for replica handshake...");
        TimeUnit.SECONDS.sleep(15);

        // 3. Start client
        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        clientLibrary = new ClientLibrary(clientContext);

        // 4. Fund the client's derived address in every replica's world state
        //    (the genesis doesn't know the client's ECDSA-derived address)
        Address clientAddress = clientContext.getSelfAddress();
        System.out.println("[TEST] Client address: " + clientAddress.toHexString());

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord, "Coordinator for " + replicaId + " should be registered");

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(clientAddress)) {
                ws.createEOA(clientAddress, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(clientAddress, BigInteger.valueOf(10_000_000));
            }
        }

        // Also ensure receiver exists so we can verify balance later
        Address receiver = Address.fromHexString(RECEIVER_HEX);
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            if (!ws.accountExists(receiver)) {
                ws.createEOA(receiver, 0, BigInteger.ZERO);
            }
        }

        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] Ending NativeTransferHotStuffTest");
        if (clientContext != null) {
            clientContext.stop();
        }
    }

    @Test
    @DisplayName("Multiple sequential native transfers all committed")
    void testMultipleSequentialTransfers() throws InterruptedException {
        TimeUnit.SECONDS.sleep(5);

        int numTransfers = 10;
        int[] reqIds = new int[numTransfers];

        for (int i = 0; i < numTransfers; i++) {
            reqIds[i] = clientContext.getRequestId().get() + 1;
            long nonce = 0; // FIXME nonce must increment
            BigInteger value = BigInteger.valueOf(100 * (i + 1));

            System.out.println("[TEST] Submitting transfer " + (i + 1) + " (reqId=" + reqIds[i]
                    + ", value=" + value + ", nonce=" + nonce + ")");
            clientLibrary.submitNativeTransfer(RECEIVER_HEX, value, GAS_PRICE, GAS_LIMIT, nonce);

            // Small delay between submissions to ensure ordering
            TimeUnit.MILLISECONDS.sleep(500);
        }

        // Wait for all to commit
        boolean allCommitted = waitForCommit(numTransfers, 180, reqIds[numTransfers - 1]);
        assertTrue(allCommitted, "All " + numTransfers + " transfers should be committed");

        // Verify committed log
        List<String> log = clientContext.getCommitedLog();
        assertEquals(numTransfers, log.size(),
                "Committed log should have " + numTransfers + " entries, got " + log.size());

        // Verify receiver balance on a replica: 100 + 200 + 300 = 600
        DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        Address receiver = Address.fromHexString(RECEIVER_HEX);
        BigInteger expectedBalance = BigInteger.valueOf(100 + 200 + 300);
        assertEquals(expectedBalance, ws.getBalance(receiver),
                "Receiver should have total transferred value");

        System.out.println("[TEST] All " + numTransfers + " transfers committed. Receiver balance: "
                + ws.getBalance(receiver));
    }

    // ========== Helpers ==========

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

    private boolean waitForCommit(int expectedSize, int timeOutSeconds, int lastRequestId)
            throws InterruptedException {
        for (int i = 0; i < timeOutSeconds; i++) {
            boolean logOk = clientContext.getCommitedLog().size() >= expectedSize;
            boolean pendingOk = !clientContext.getPendingRequests().containsKey(lastRequestId);
            if (logOk && pendingOk) {
                return true;
            }
            TimeUnit.SECONDS.sleep(1);
        }
        System.err.println("[TEST] Timeout! commitedLog.size()=" + clientContext.getCommitedLog().size()
                + ", pendingRequests=" + clientContext.getPendingRequests().keySet());
        return false;
    }
}
