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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: submits multiple native DepCoin transfers through the full
 * BasicHotStuff consensus (4 replicas, 4 HotStuff phases) and verifies that
 * all transfers are committed and the receiver balance is correct.
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
                ws.createEOA(clientAddress, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(clientAddress, BigInteger.valueOf(10_000_000));
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
        System.out.println("[TEST] Ending NativeTransferHotStuffTest");
        if (clientContext != null) {
            clientContext.stop();
        }
        ServerApp.stopAll();
    }

    @Test
    @DisplayName("Three sequential native transfers - each blocks until committed")
    void testMultipleSequentialTransfers() throws InterruptedException {
        TimeUnit.SECONDS.sleep(5);

        int numTransfers = 3;
        long totalValue = 0;

        for (int i = 0; i < numTransfers; i++) {
            BigInteger value = BigInteger.valueOf(100L * (i + 1));
            totalValue += 100L * (i + 1);
            System.out.println("[TEST] Submitting transfer " + (i + 1) + " (value=" + value + ")");
            // Blocking - returns only after f+1 commit responses; nonce auto-increments
            clientLibrary.submitNativeTransfer(RECEIVER_HEX, value, GAS_PRICE, GAS_LIMIT);
            System.out.println("[TEST] Transfer " + (i + 1) + " confirmed");
        }

        // All transfers are committed by the time we reach here
        assertEquals(numTransfers, clientContext.getCommitedLog().size(),
                "Committed log should have " + numTransfers + " entries");

        DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        Address receiver = Address.fromHexString(RECEIVER_HEX);
        assertEquals(BigInteger.valueOf(totalValue), ws.getBalance(receiver),
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
}
