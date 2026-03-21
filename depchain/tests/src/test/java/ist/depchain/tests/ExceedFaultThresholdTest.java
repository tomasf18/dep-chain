package ist.depchain.tests;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ExceedFaultThresholdTest  —  boundary / negative test
 *
 * Setup: only 2 of the 4 replicas are started (s0 and s3).
 * With n=4 and f=1 the quorum threshold is n-f = 3.  Because only 2 processes
 * are reachable, no quorum can ever be formed and the system must NOT commit
 * any request within the observation window.
 *
 * What is tested:
 *   - Liveness is correctly impossible when the number of reachable honest
 *     replicas falls below the quorum threshold (n - f = 3).
 *   - No safety violation occurs either: nothing is committed under these
 *     conditions, which is the correct behaviour.
 *
 * This is a negative test — the assertion is that the commit log remains empty.
 */
public class ExceedFaultThresholdTest {
    private static final String CONFIG_FILE = "../config-test.json";
    // How long (seconds) to wait before declaring liveness impossible.
    // Must be longer than several view-timer cycles so we are sure the system
    // has had enough time to try and fail.
    private static final int OBSERVATION_WINDOW_S = 60;

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting ExceedFaultThresholdTest (only 2 of 4 replicas online)");

        // Only start s0 and s3 — s1 and s2 are treated as crashed.
        startReplica("s0");
        startReplica("s3");

        System.out.println("[TEST] - s1 and s2 are offline (simulated crash, quorum unreachable)");

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Stopping ExceedFaultThresholdTest");
        if (clientContext != null) clientContext.stop();
    }

    @Test
    @DisplayName("System cannot commit when fewer than n-f replicas are reachable (liveness boundary)")
    public void testNoCommitBelowQuorum() throws InterruptedException {
        System.out.println("[TEST] - Sending client request (expecting no commit)");
        clientLibrary.append("Request that should never be committed");

        // Observe for OBSERVATION_WINDOW_S seconds; no commit should occur.
        TimeUnit.SECONDS.sleep(OBSERVATION_WINDOW_S);

        assertEquals(0, clientContext.getCommitedLog().size(),
                "Liveness boundary violated: a commit occurred with only 2 of 4 replicas online");

        System.out.println("[TEST] - Passed: no commit achieved with quorum unreachable (correct behaviour)");
    }

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, serverId, "false", "SILENT"});
            } catch (Exception e) {
                System.out.println("[TEST] - Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
