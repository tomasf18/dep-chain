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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ByzantineCorruptReplicaTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() {
        System.out.println("[TEST] - Starting Client");
        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Ending ByzantineTest");
        if (clientContext != null) {
            clientContext.stop();
        }
        // Replicas Threads are DAEMON therefore they terminate automatically after the TEST ends
    }

    @Test
    @DisplayName("Verify that a test still goes through if one replica is byzantine and changes the content of the message")
    public void testByzantineReplica() throws Exception {
        System.out.println("[TEST] - Byzantine Replica");

        System.out.println("[TEST] - Starting Replicas");
        startReplica("s0", "false");
        startReplica("s1", "false");
        startReplica("s2", "true");
        startReplica("s3", "false");

        System.out.println("[TEST] - Waiting for Replicas Handshake");
        // 5 seconds waiting necessary for the Handshake to be made as the method handshakeAll() runs on a separate thread
        TimeUnit.SECONDS.sleep(5);

        String request = "Testing project for Byzantine Replica Test";
        System.out.println("[TEST] - Client sending request: " + request);

        int currentId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(request);

        // Dynamic Wait
        System.out.println("[TEST] - Final Verification");
        boolean success = waitForCommit(1, 200, currentId);
        assertTrue(success, "Request wasn't commited in the expect time");

        // Content Validation
        List<String> log = clientContext.getCommitedLog();
        assertTrue(log.contains(request), "Request log contains not commited");
        assertFalse(log.contains("MALICIOUS DATA ALTERED BY BYZANTINE"), "Malicious replica inserted false message");

        System.out.println("[TEST] - Client received f+1 ACKs and request " + currentId + " has been commited");

        // Logs
        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(60);
    }

    private static void startReplica(String serverId, String isByzantine){
        Thread t = new Thread(() -> {
            try{
                ServerApp.main(new String[]{CONFIG_FILE, serverId, isByzantine, "CORRUPT"});
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
            if(this.clientContext.getCommitedLog().size() >= expectedSize && !clientContext.getPendingRequests().containsKey(requestId)){
                return true;
            }
            TimeUnit.SECONDS.sleep(1);
        }
        return false;
    }
}
