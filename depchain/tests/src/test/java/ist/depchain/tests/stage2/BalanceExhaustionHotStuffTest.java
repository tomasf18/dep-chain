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
 * Integration test: verifies that when a client submits multiple transactions
 * that collectively exceed its balance, only the highest-fee transactions
 * execute successfully while the rest fail mid-block.
 *
 * All transactions are still committed to the chain (committed=true in the
 * ClientResponse), since consensus commitment and execution success are
 * independent concepts. Failed transactions consume no balance.
 *
 * Fee ordering guarantees deterministic execution order across replicas:
 *   tx0 (gasPrice=3, fee=63_000) → tx1 (gasPrice=2, fee=42_000) → tx2 (gasPrice=1, fee=21_000)
 *
 * With client balance=100_000:
 *   tx0 upfront = 30_000 + 63_000 = 93_000 → succeeds, balance = 7_000
 *   tx1 upfront = 30_000 + 42_000 = 72_000 > 7_000 → fails
 *   tx2 upfront = 30_000 + 21_000 = 51_000 > 7_000 → fails
 */
public class BalanceExhaustionHotStuffTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    private static final String RECEIVER_HEX = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger CLIENT_BALANCE = BigInteger.valueOf(100_000);

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws Exception {
        System.out.println("[TEST] Starting BalanceExhaustionHotStuffTest");

        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        System.out.println("[TEST] Waiting for replica handshake...");
        TimeUnit.SECONDS.sleep(15);

        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        clientLibrary = new ClientLibrary(clientContext);

        Address clientAddress = clientContext.getSelfAddress();
        System.out.println("[TEST] Client address: " + clientAddress.toHexString());

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord, "Coordinator for " + replicaId + " should be registered");

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(clientAddress)) {
                ws.createEOA(clientAddress, 0, CLIENT_BALANCE);
            } else {
                ws.addBalance(clientAddress, CLIENT_BALANCE);
            }
        }

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
        System.out.println("[TEST] Ending BalanceExhaustionHotStuffTest");
        if (clientContext != null) {
            clientContext.stop();
        }
        //ServerApp.stopAll();
    }

    @Test
    @DisplayName("Only highest-fee tx executes when balance covers exactly one tx")
    void testInsufficientBalanceCausesPartialBlockExecution() throws InterruptedException {
        TimeUnit.SECONDS.sleep(5);

        int numTxs = 3;
        int[] reqIds = new int[numTxs];

        // Descending gasPrice forces fee order: tx0 first, tx2 last
        BigInteger[] gasPrices = {BigInteger.valueOf(3), BigInteger.valueOf(2), BigInteger.ONE};
        for (int i = 0; i < numTxs; i++) {
            reqIds[i] = clientContext.getRequestId().get() + 1;
            System.out.println("[TEST] Submitting tx " + i + " (reqId=" + reqIds[i]
                    + ", gasPrice=" + gasPrices[i] + ", nonce=" + i + ")");
            clientLibrary.submitNativeTransfer(RECEIVER_HEX, BigInteger.valueOf(30_000),
                    gasPrices[i], GAS_LIMIT, i);
            TimeUnit.MILLISECONDS.sleep(500);
        }

        // All 3 get committed=true responses (execution failure ≠ consensus failure)
        boolean allCommitted = waitForCommit(numTxs, 180, reqIds[numTxs - 1]);
        assertTrue(allCommitted, "All 3 txs should receive committed responses");

        List<String> log = clientContext.getCommitedLog();
        assertEquals(numTxs, log.size(), "Committed log should have 3 entries");

        // Only tx0 (value=30_000) actually transferred funds
        DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        Address receiver = Address.fromHexString(RECEIVER_HEX);
        assertEquals(BigInteger.valueOf(30_000), ws.getBalance(receiver),
                "Receiver should have received only the first transfer");

        Address clientAddress = clientContext.getSelfAddress();
        System.out.println("[TEST] Receiver balance: " + ws.getBalance(receiver)
                + ", client balance: " + ws.getBalance(clientAddress));
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
