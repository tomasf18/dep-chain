package ist.depchain.tests;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.byzantine.ByzantineCoordinator.AttackType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ByzantineAndCrashedReplicaTest  —  compound fault / negative test
 *
 * Setup: two replicas are started (s0 honest, s1 Byzantine SILENT); the other
 * two (s2, s3) are treated as crashed and never started.
 *
 * With n=4, f=1 the quorum is n-f = 3.  Only two processes are reachable, so
 * no quorum can ever be reached regardless of Byzantine behaviour.  The test
 * demonstrates that a combination of crash faults AND Byzantine faults that
 * together exceed the fault tolerance makes progress impossible.
 *
 * What is tested:
 *   - Compound fault scenario: 1 crash fault (s2, s3 absent) + 1 Byzantine
 *     (s1 SILENT) leaves only 2 participating processes — below quorum.
 *   - No commit must occur during the observation window (correct behaviour).
 */
public class ByzantineAndCrashedReplicaTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private static final int OBSERVATION_WINDOW_S = 60;

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting ByzantineAndCrashedReplicaTest");

        // s0: honest replica
        startReplica("s0", false, AttackType.SILENT);
        // s1: Byzantine SILENT (suppresses proposals and vote processing when leader)
        startReplica("s1", true, AttackType.SILENT);
        // s2 and s3: not started (simulated crash)
        System.out.println("[TEST] - s2 and s3 are offline (simulated crash)");
        System.out.println("[TEST] - s1 is Byzantine SILENT — combined faults exceed f=1");

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Stopping ByzantineAndCrashedReplicaTest");
        if (clientContext != null) clientContext.stop();
    }

    @Test
    @DisplayName("No commit when compound crash+Byzantine faults reduce active replicas below quorum")
    public void testNoCommitWithCompoundFaults() throws InterruptedException {
        System.out.println("[TEST] - Sending request (expecting no commit — quorum unreachable)");
        clientLibrary.append("Request under compound fault scenario");

        TimeUnit.SECONDS.sleep(OBSERVATION_WINDOW_S);

        assertEquals(0, clientContext.getCommitedLog().size(),
                "Liveness boundary violated: commit occurred despite compound crash+Byzantine fault");

        System.out.println("[TEST] - Passed: no commit with 2 crashed + 1 Byzantine replica (correct behaviour)");
    }

    private static void startReplica(String serverId, boolean isByzantine, AttackType attack) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, serverId,
                        String.valueOf(isByzantine), attack.toString()});
            } catch (Exception e) {
                System.out.println("[TEST] - Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
