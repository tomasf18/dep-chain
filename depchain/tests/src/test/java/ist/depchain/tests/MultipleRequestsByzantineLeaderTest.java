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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MultipleRequestsByzantineLeaderTest
 *
 * Attack vector: s1 (leader of view 1) is Byzantine EQUIVOCATE throughout the
 * entire test run.  After the first view change, an honest leader (s2) takes
 * over and the system commits.  The test then immediately sends two more requests
 * to verify that subsequent rounds also complete correctly with the persistent
 * Byzantine node still present.
 *
 * What is tested:
 *   - Safety across multiple rounds: no equivocated block (BLOCK_A / BLOCK_B)
 *     ever appears in the committed log.
 *   - Liveness across multiple rounds: all three client requests are eventually
 *     committed with the correct content.
 *   - The system does not re-elect or stall on the Byzantine leader after the
 *     first view change — honest leaders continue to drive progress.
 */
public class MultipleRequestsByzantineLeaderTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private static final int NUM_REQUESTS = 3;

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting MultipleRequestsByzantineLeaderTest");

        startReplica("s0", false, AttackType.SILENT);        // honest
        startReplica("s1", true,  AttackType.EQUIVOCATE);    // byzantine, leader of view 1
        startReplica("s2", false, AttackType.SILENT);        // honest
        startReplica("s3", false, AttackType.SILENT);        // honest

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Stopping MultipleRequestsByzantineLeaderTest");
        if (clientContext != null) clientContext.stop();
    }

    @Test
    @DisplayName("All requests committed correctly across multiple rounds with persistent equivocating leader")
    public void testMultipleRequestsWithByzantineLeader() throws InterruptedException {
        String[] requests = new String[NUM_REQUESTS];
        int[] requestIds = new int[NUM_REQUESTS];

        // Send all requests sequentially, waiting for each to commit before sending the next.
        // This exercises multiple independent consensus rounds while s1 stays Byzantine.
        for (int i = 0; i < NUM_REQUESTS; i++) {
            requests[i] = "Request-" + (i + 1) + " under persistent equivocating leader";
            requestIds[i] = clientContext.getRequestId().get() + 1;

            System.out.println("[TEST] - Sending request " + (i + 1) + ": " + requests[i]);
            clientLibrary.append(requests[i]);

            boolean committed = waitForCommit(i + 1, 120, requestIds[i]);
            assertTrue(committed,
                    "Liveness failure: request " + (i + 1) + " was not committed within timeout");

            System.out.println("[TEST] - Request " + (i + 1) + " committed successfully");
        }

        // Safety check: no equivocated block should ever appear.
        List<String> log = clientContext.getCommitedLog();
        assertFalse(log.contains("BLOCK_A"), "Safety violation: equivocated BLOCK_A was committed");
        assertFalse(log.contains("BLOCK_B"), "Safety violation: equivocated BLOCK_B was committed");

        // Content check: all original requests must be present.
        for (String request : requests) {
            assertTrue(log.contains(request),
                    "Request missing from committed log: " + request);
        }

        System.out.println("[TEST] - Passed: all " + NUM_REQUESTS
                + " requests committed, no equivocated blocks, safety and liveness verified");
        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(10);
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

    private boolean waitForCommit(int expectedSize, int timeoutSeconds, int requestId)
            throws InterruptedException {
        for (int i = 0; i < timeoutSeconds; i++) {
            if (clientContext.getCommitedLog().size() >= expectedSize
                    && !clientContext.getPendingRequests().containsKey(requestId))
                return true;
            TimeUnit.SECONDS.sleep(1);
        }
        return false;
    }
}
