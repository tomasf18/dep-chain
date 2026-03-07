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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Happy Path test assumes:
 *  All replicas connected are honest
 *  Even though we could have losses in messages, the network is reliable enough for the protocol to end
 *  No deliberate Byzantine attacks
 * This test is purely made to validate the base functionality of the protocol, according to Liveness (the system progresses) and safety (everyone agrees with the log).
 */
public class HappyPathTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() {
        System.out.println("[TEST] - Starting HappyPathTest");
        String[] replicas = {"s0", "s1", "s2", "s3"};

        System.out.println("[Test] - Starting Replicas");
        for (String replica : replicas) {startReplica(replica);}

        System.out.println("[TEST] - Waiting for Replicas Handshake");
        try {
            // 5 seconds waiting necessary for the Handshake to be made as the method handshakeAll() runs on a separate thread
            TimeUnit.SECONDS.sleep(5);
        }catch(Exception e) {
            e.printStackTrace();
        }

        System.out.println("[TEST] - Starting Client");
        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Ending HappyPathTest");
        if (clientContext != null) {
            clientContext.stop();
        }
        // Replicas Threads are DAEMON therefore they terminate automatically after the test ends
    }

    @Test
    @DisplayName("Verify that a request is commited by the quorum in an ideal condition")
    void testHappyPath() {
        String request = "Testing project for HappyPathTest";
        System.out.println("[TEST] - Client sending request: " + request);

        int currentId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(request);

        try{
            // HotStuff protocol has lots of phases, we wait 10 seconds as messages could be lost and therefore delaying the overall performance/end of the protocol
            TimeUnit.SECONDS.sleep(10);
        }catch(Exception e) {
            e.printStackTrace();
        }

        System.out.println("[TEST] - Final Verification");

        boolean isCommited = !clientContext.getPendingRequests().containsKey(currentId);
        assertTrue(isCommited, "Request not commited");
        System.out.println("[TEST] - Client received f+1 ACKs and request " + currentId + " has been commited");

        clientLibrary.showLog();
        try{TimeUnit.SECONDS.sleep(10);}catch(Exception e) {e.printStackTrace();}
    }

    private static void startReplica(String serverId){
        Thread t = new Thread(() -> {
            try{
                ServerApp.main(new String[]{CONFIG_FILE, serverId, "false"});
                System.out.println("[TEST] - Replica " + serverId + " started");
            }catch (Exception e){
                System.out.println("[TEST] - Error starting replica " + serverId + " in HappyPathTest");
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
