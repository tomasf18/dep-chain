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
 * Resilience test assumes:
 *  All replicas connected are honest
 *  Even though we could have losses in messages, the network is reliable enough for the protocol to end
 *  No deliberate Byzantine attacks
 *  One replica "s3" crashed
 * This test is purely made to validate the base functionality of the protocol, according to Liveness (the system progresses) and safety (everyone agrees with the log).
 */
public class ResilienceTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException{
        System.out.println("[TEST] - Starting ResilienceTest (f=1 Failure)");
        String[] replicas = {"s0", "s1", "s2"};

        System.out.println("[TEST] - Starting Replicas");
        for (String replica : replicas) {startReplica(replica);}

        System.out.println("[TEST] - Replica s3 is offline (Simulating Crash)");

        System.out.println("[TEST] - Waiting for Replicas Handshake");

        // 5 seconds waiting necessary for the Handshake to be made as the method handshakeAll() runs on a separate thread
        TimeUnit.SECONDS.sleep(5);

        System.out.println("[TEST] - Starting Client");
        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Ending ResilianceTest");
        if (clientContext != null) {
            clientContext.stop();
        }
        // Replicas Threads are DAEMON therefore they terminate automatically after the TEST ends
    }

    @Test
    @DisplayName("Verify that a request is commited by the quorum with one crashed process")
    void testResilience() throws InterruptedException {
        TimeUnit.SECONDS.sleep(10);
        String request = "Testing project for ResilianceTest";
        System.out.println("[TEST] - Client sending request: " + request);

        int currentId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(request);

        // HotStuff protocol has lots of phases, we wait 10 seconds as messages could be lost and therefore delaying the overall performance/end of the protocol

        System.out.println("[TEST] - Final Verification");

        boolean isCommited = waitForCommit(1, 60, currentId);
        assertTrue(isCommited, "Request not commited");

        //Content validation
        List<String> commitedLog = clientContext.getCommitedLog();
        assertTrue(commitedLog.contains(request), "Request not commited");

        System.out.println("[TEST] - Client received f+1 ACKs and request " + currentId + " has been commited");

        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(30);
    }

    private static void startReplica(String serverId){
        Thread t = new Thread(() -> {
            try{
                ServerApp.main(new String[]{CONFIG_FILE, serverId, "false"});
            }catch (Exception e){
                System.out.println("[TEST] - Error starting replica " + serverId + " in ResilienceTest");
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