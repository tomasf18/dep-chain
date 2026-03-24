package ist.depchain.tests.stage1;

import com.google.protobuf.ByteString;
import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.Command;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.network.crypto.Crypto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReplayAttackTest
 *
 * Attack vector: After a client request is legitimately committed (requestId = N),
 * the test re-crafts a ClientRequest protobuf with the same requestId N, signs it
 * with the real client key, and broadcasts it directly to all replicas - bypassing
 * the ClientLibrary's auto-increment.
 *
 * What is tested:
 *   - The server-side replay protection in MessageHandler (lastSeenRequestId map).
 *     Any request whose requestId <= the last accepted requestId for that client
 *     must be silently dropped without entering consensus.
 *   - The committed log must remain at size 1; no second consensus round is started.
 */
public class ReplayAttackTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting ReplayAttackTest");

        for (String id : new String[]{"s0", "s1", "s2", "s3"}) startReplica(id);

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Stopping ReplayAttackTest");
        if (clientContext != null) clientContext.stop();
    }

    @Test
    @DisplayName("A replayed client request (same requestId) is rejected by server replay protection")
    public void testReplayIsRejected() throws Exception {
        // ── Step 1: commit a legitimate request ──────────────────────────────────
        String originalData = "Legitimate request";
        int legitimateId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(originalData);

        boolean committed = waitForCommit(1, 60, legitimateId);
        assertTrue(committed, "Legitimate request was not committed");
        assertTrue(clientContext.getCommitedLog().contains(originalData),
                "Committed log does not contain the original request");

        System.out.println("[TEST] - Legitimate request committed. Sending replay...");

        // ── Step 2: craft a replay of requestId=legitimateId ─────────────────────
        Command command = Command.newBuilder()
                .setType("append")
                .setData(originalData)
                .build();

        ClientRequest unsigned = ClientRequest.newBuilder()
                .setClientId(clientContext.getConfig().getSelfId())
                .setRequestId(legitimateId) // same requestId as the committed one
                .setCommand(command)
                .build();

        byte[] sig = Crypto.sign(
                unsigned.toByteArray(),
                clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        ClientRequest replay = ClientRequest.newBuilder(unsigned)
                .setSignature(ByteString.copyFrom(sig))
                .build();

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(replay)
                .build();

        Set<String> servers = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(servers, appMsg.toByteArray());

        // ── Step 3: wait and verify the log did NOT grow ─────────────────────────
        TimeUnit.SECONDS.sleep(15);

        assertEquals(1, clientContext.getCommitedLog().size(),
                "Safety violation: replay caused a second commit");

        System.out.println("[TEST] - Passed: replay correctly rejected, log size unchanged at 1");
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
