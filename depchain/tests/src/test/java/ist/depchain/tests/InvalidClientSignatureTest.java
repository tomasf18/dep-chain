package ist.depchain.tests;

import com.google.protobuf.ByteString;
import ist.depchain.client.ClientContext;
import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.Command;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * InvalidClientSignatureTest
 *
 * Attack vector: A malicious client crafts a ClientRequest protobuf whose
 * clientSignature field contains random garbage bytes (no real private key).
 * The request is broadcast directly to all replicas, bypassing ClientLibrary.
 *
 * What is tested:
 *   - MessageHandler.verifyClientSignature() must reject any request whose
 *     ECDSA signature does not verify against the registered public key.
 *   - No consensus round should be started; the committed log stays empty.
 */
public class InvalidClientSignatureTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting InvalidClientSignatureTest");

        for (String id : new String[]{"s0", "s1", "s2", "s3"}) startReplica(id);

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Stopping InvalidClientSignatureTest");
        if (clientContext != null) clientContext.stop();
    }

    @Test
    @DisplayName("A request with a forged/random ECDSA signature is rejected by all replicas")
    public void testInvalidSignatureRejected() throws Exception {
        System.out.println("[TEST] - Sending request with forged signature");

        // Build a ClientRequest signed with random bytes instead of the real key.
        Command command = Command.newBuilder()
                .setType("append")
                .setData("Forged request — should never be committed")
                .build();

        byte[] forgedSig = new byte[64];
        new Random().nextBytes(forgedSig);

        ClientRequest forgedRequest = ClientRequest.newBuilder()
                .setClientId(clientContext.getConfig().getSelfId())
                .setRequestId(1)
                .setCommand(command)
                .setSignature(ByteString.copyFrom(forgedSig))
                .build();

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(forgedRequest)
                .build();

        Set<String> servers = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(servers, appMsg.toByteArray());

        // Wait long enough for the message to be received and processed.
        // No response should arrive and the log must remain empty.
        TimeUnit.SECONDS.sleep(20);

        assertEquals(0, clientContext.getCommitedLog().size(),
                "Safety violation: request with forged signature was committed");

        System.out.println("[TEST] - Passed: forged-signature request correctly rejected by all replicas");
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
