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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ByzantineTest {
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
        // HotStuff protocol has lots of phases, we wait 10 seconds as messages could be lost and therefore delaying the overall performance/end of the protocol
        TimeUnit.SECONDS.sleep(10);

        System.out.println("[TEST] - Final Verification");
        boolean isCommited = !clientContext.getPendingRequests().containsKey(currentId);
        assertTrue(isCommited, "Request not commited");
        System.out.println("[TEST] - Client received f+1 ACKs and request " + currentId + " has been commited");

        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(10);
    }

    @Test
    @DisplayName("Verify that honest replicas will timeout and request a NEW_VIEW, rotating the byzantine leader")
    public void testByzantineLeader() throws Exception {
        System.out.println("[TEST] - Byzantine Replica");

        System.out.println("[TEST] - Starting Replicas");
        startReplica("s0", "false");
        startReplica("s1", "true");
        startReplica("s2", "false");
        startReplica("s3", "false");

        System.out.println("[TEST] - Waiting for Replicas Handshake");
        // 5 seconds waiting necessary for the Handshake to be made as the method handshakeAll() runs on a separate thread
        TimeUnit.SECONDS.sleep(20);

        String request = "Testing project for Byzantine Leader Test";
        System.out.println("[TEST] - Client sending request: " + request);

        int currentId = clientContext.getRequestId().get() + 1;
        clientLibrary.append(request);
        // HotStuff protocol has lots of phases, we wait 10 seconds as messages could be lost and therefore delaying the overall performance/end of the protocol
        TimeUnit.SECONDS.sleep(20);

        System.out.println("[TEST] - Final Verification");
        boolean isCommited = !clientContext.getPendingRequests().containsKey(currentId);
        assertTrue(isCommited, "Request not commited");
        System.out.println("[TEST] - Client received f+1 ACKs and request " + currentId + " has been commited");

        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(20);
    }

    private static void startReplica(String serverId, String isByzantine){
        Thread t = new Thread(() -> {
            try{
                ServerApp.main(new String[]{CONFIG_FILE, serverId, isByzantine});
            }catch (Exception e){
                System.out.println("[TEST] - Error starting replica " + serverId + " in ResilienceTest");
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
