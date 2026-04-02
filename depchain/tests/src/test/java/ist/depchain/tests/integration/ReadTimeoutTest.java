package ist.depchain.tests.integration;

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

/**
 * Tests that a consensus-based balance read times out when consensus
 * cannot make progress.
 *
 * Reads are now normal transactions that go through HotStuff consensus.
 * With f=1 and 4 replicas, consensus requires 2f+1 = 3 replicas.
 * If 2 replicas are stopped, only 2 remain and consensus cannot form
 * a quorum, so the balance read must time out.
 *
 * Scenario:
 * - s0, s1: honest, running -> only 2 replicas available
 * - s2: stopped before read
 * - s3: stopped before read
 *
 * The client submits a native balance query. Since only 2 out of 4
 * replicas are available (less than the required 2f+1 = 3), consensus
 * cannot proceed and the request should fail with a timeout.
 */
class ReadTimeoutTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };

    private static final BigInteger ADDED_NATIVE_BALANCE = BigInteger.valueOf(10_000_000);

    private ClientContext client1Context;
    private ClientLibrary client1Library;

    @BeforeEach
    void setup() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config client1Config = Config.loadConfiguration(CONFIG_FILE, "client1");
        client1Context = new ClientContext(client1Config);
        MessageHandler client1Handler = new MessageHandler(client1Context);
        client1Library = new ClientLibrary(client1Context, client1Handler);

        Address client1Address = client1Context.getSelfAddress();

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord);

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(client1Address)) {
                ws.createEOA(client1Address, 0, ADDED_NATIVE_BALANCE);
            } else {
                ws.addBalance(client1Address, ADDED_NATIVE_BALANCE);
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        client1Context.setRequestId((int) requestBase);
        client1Context.setNonce(
                ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client1Address));

        client1Context.start();
    }

    @AfterEach
    void teardown() {
        if (client1Context != null)
            client1Context.stop();
        stopReplicas();
    }

    @Test
    void readTimesOutWhenConsensusCannotFormQuorum() throws Exception {
        // Stop 2 replicas: with only 2 out of 4 remaining, consensus cannot
        // form the required 2f+1 = 3 quorum
        ServerApp.getCoordinator("s2").getServerContext().stop();
        ServerApp.getCoordinator("s2").stop();
        ServerApp.getCoordinator("s3").getServerContext().stop();
        ServerApp.getCoordinator("s3").stop();

        // The balance read goes through consensus, which cannot proceed -> timeout
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client1Library.submitNativeBalanceCheck(),
                "read should time out when consensus cannot form a quorum");
        assertTrue(ex.getMessage().toLowerCase().contains("timeout")
                || ex.getMessage().toLowerCase().contains("timed out")
                || ex.getMessage().toLowerCase().contains("failed"),
                "exception should indicate timeout or failure, got: " + ex.getMessage());
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
}
