package ist.depchain.tests;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthenticationTamperingTest
 *
 * Attack vector: The network is configured with tamperProbability = 0.5, meaning
 * roughly half of all outgoing UDP packets have their payload bytes corrupted
 * by the UdpFairLossLink layer before transmission.  A corrupted packet will
 * fail the HMAC-SHA256 check inside AuthenticatedPerfectLink and be silently
 * dropped.  The StubbornLink retransmission layer will keep resending until the
 * authentic copy of the message gets through.
 *
 * What is tested:
 *   - HMAC verification correctly discards tampered messages (authentication layer).
 *   - The stubborn retransmission layer compensates for the high drop rate caused
 *     by HMAC failures, ensuring eventual delivery of untampered messages.
 *   - Liveness: the system commits the request despite 50 % message corruption.
 *   - Safety: the committed data is the original request, not tampered content.
 *
 * Uses config-tamper.json (tamperProbability=0.5, dropProbability=0,
 * duplicateProbability=0) so that all non-committed progress is purely due to
 * HMAC failures on tampered packets.
 */
public class AuthenticationTamperingTest {
    private static final String CONFIG_FILE = "../config-tamper.json";
    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting AuthenticationTamperingTest (tamperProbability=0.5)");

        for (String id : new String[]{"s0", "s1", "s2", "s3"}) startReplica(id);

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Stopping AuthenticationTamperingTest");
        if (clientContext != null) clientContext.stop();
    }

    @Test
    @DisplayName("System commits correctly despite 50% HMAC-level message tampering")
    public void testLivenessDespiteTampering() throws InterruptedException {
        System.out.println("[TEST] - Sending client request under high tampering conditions");
        String request = "Request under 50% message tampering";
        int requestId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(request);

        // Allow generous time: with 50% tampering the stubborn layer may need
        // many retransmissions before an untampered copy gets through each hop.
        boolean committed = waitForCommit(1, 200, requestId);
        assertTrue(committed,
                "Liveness failure: system did not commit request despite stubborn retransmission");

        List<String> log = clientContext.getCommitedLog();
        assertTrue(log.contains(request),
                "Safety failure: committed log does not contain the original request");

        System.out.println("[TEST] - Passed: request committed correctly despite 50% tampering");
        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(10);
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
