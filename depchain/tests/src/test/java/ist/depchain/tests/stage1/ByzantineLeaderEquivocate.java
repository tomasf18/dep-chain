package ist.depchain.tests.stage1;

import ist.depchain.client.ClientContext;
import ist.depchain.client.MessageHandler;
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

public class ByzantineLeaderEquivocate {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;
    private MessageHandler messageHandler;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws Exception {
        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        messageHandler = new MessageHandler(clientContext);
        clientLibrary = new ClientLibrary(clientContext, messageHandler);
        clientContext.start();
    }

    @AfterEach
    public void teardown() throws Exception {
        System.out.println("[TEST] - Stopping Client");
        if(clientContext != null) {
            clientContext.stop();
        }
    }

    @Test
    @DisplayName("Verify that a Leader sending different blocks to different blocks does not cause a fork and the system recovers")
    public void testLeaderEquivocate() throws InterruptedException{
        System.out.println("[TEST] - Starting testLeaderEquivocate");

        startReplica("s0", "false", AttackType.valueOf("SILENT")); //Honest replica
        startReplica("s1", "true", AttackType.valueOf("EQUIVOCATE"));
        startReplica("s2", "false", AttackType.valueOf("SILENT"));
        startReplica("s3", "false", AttackType.valueOf("SILENT"));

        System.out.println("[TEST] - Waiting for replicas handshake");
        TimeUnit.SECONDS.sleep(8);

        String request = "Critical request";
        int currentId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(request);

        //Wait a few seconds to check if a not desired request was commited
        TimeUnit.SECONDS.sleep(10);
        List<String> logPreTimeout = clientContext.getCommitedLog();

        assertFalse(logPreTimeout.contains("BLOCK_A"), "Safety Violation");
        assertFalse(logPreTimeout.contains("BLOCK_B"), "Safety Violation");

        System.out.println("[TEST] - Safety Ok, No conflicting blocks were commited during leader equivocation");

        boolean isSuccess = waitForCommit(1, 120, currentId);
        assertTrue(isSuccess, "System failed to recover and commit request");

        List<String> logPostTimeout = clientContext.getCommitedLog();
        assertTrue(logPostTimeout.contains(request), "The original client request was lost");

        System.out.println("[TEST] - Test terminated, passed both liveness and safety check");

        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(40);
    }

    private static void startReplica(String serverId, String isByzantine, AttackType attackType){
        Thread t = new Thread(() -> {
            try{
                ServerApp.main(new String[]{CONFIG_FILE, serverId, isByzantine, attackType.toString()});
            }catch (Exception e){
                System.out.println("[TEST] - Error starting replica " + serverId + " in ByzantineTest");
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private boolean waitForCommit(int expectedSize, int timeOutSeconds, int requestId) throws InterruptedException {
        for (int i = 0; i < timeOutSeconds; i++) {
            if(this.clientContext.getCommitedLog().size() >= expectedSize && !messageHandler.getPendingRequests().containsKey(requestId)){
                return true;
            }
            TimeUnit.SECONDS.sleep(1);
        }
        return false;
    }
}
