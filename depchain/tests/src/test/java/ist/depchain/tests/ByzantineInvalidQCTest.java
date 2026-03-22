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
 * ByzantineInvalidQCTest
 *
 * Attack vector: The Byzantine leader (s1, view 1) sends a PREPARE message whose
 * highQC carries a forged threshold signature (random 96 bytes).  Honest replicas
 * call verifyQC() on the justify field before voting; the BLS check fails and the
 * proposal is silently dropped.  The view timer fires, honest nodes rotate to
 * leader s2 (view 2), which proposes correctly and the request is committed.
 *
 * What is tested:
 *   - Safety: the block with an invalid QC is NEVER committed.
 *   - Liveness: the system view-changes and eventually commits the real request.
 */
public class ByzantineInvalidQCTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting ByzantineInvalidQCTest");

        startReplica("s0", false, AttackType.SILENT);   // honest
        startReplica("s1", true,  AttackType.INVALID_QC); // byzantine leader (view 1)
        startReplica("s2", false, AttackType.SILENT);   // honest
        startReplica("s3", false, AttackType.SILENT);   // honest

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Stopping ByzantineInvalidQCTest");
        if (clientContext != null) clientContext.stop();
    }

    @Test
    @DisplayName("Honest replicas reject a PREPARE with a forged QC; system recovers via view change")
    public void testInvalidQCRejected() throws InterruptedException {
        System.out.println("[TEST] - Sending client request");
        String request = "Request after invalid QC proposal";
        int currentId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(request);

        // Allow time for the invalid proposal to be received and rejected, then view-change.
        TimeUnit.SECONDS.sleep(5);
        List<String> logDuringAttack = clientContext.getCommitedLog();
        assertFalse(logDuringAttack.contains("INVALID_QC_BLOCK"),
                "Safety violation: block backed by forged QC was committed");

        System.out.println("[TEST] - Safety OK: invalid-QC block not committed; waiting for recovery");

        boolean recovered = waitForCommit(1, 120, currentId);
        assertTrue(recovered, "Liveness failure: system did not recover and commit after invalid-QC view change");

        List<String> logAfterRecovery = clientContext.getCommitedLog();
        assertTrue(logAfterRecovery.contains(request),
                "The original client request was lost after recovery");

        System.out.println("[TEST] - Passed: invalid QC rejected, request eventually committed");
        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(30);
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
