package ist.depchain.tests.stage1;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultipleRequestsTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting HappyPathTest");
        String[] replicas = {"s0", "s1", "s2", "s3"};

        System.out.println("[Test] - Starting Replicas");
        for (String replica : replicas) {startReplica(replica);}

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
        System.out.println("[TEST] - Ending HappyPathTest");
        if (clientContext != null) {
            clientContext.stop();
        }
        // Replicas Threads are DAEMON therefore they terminate automatically after the test ends
    }

    @Test
    @DisplayName("Verify that the system can handle multiple requests and maintains order")
    void testMultipleRequests() throws InterruptedException {
        String[] requests = {"Request1", "Request2", "Request3", "Request4"};
        System.out.println("[TEST] - Client multiple requests:");
        TimeUnit.SECONDS.sleep(20);
        for (String request : requests) {
            clientLibrary.append(request);
        }
        // HotStuff protocol has lots of phases, we wait 70 seconds as messages could be lost and therefore delaying the overall performance/end of the protocol
        int time = 120;
        while(time > 0 && requests.length > clientContext.getCommitedLog().size()) {
            TimeUnit.SECONDS.sleep(1);
            time --;
        }

        System.out.println("[TEST] - Final Verification");
        List<String> committedLog = clientContext.getCommitedLog();
        assertEquals(committedLog.size(), requests.length, "Committed log size mismatch");
        for(int i = 0; i < requests.length; i++) {
            String expected = requests[i];
            String actual = committedLog.get(i);
            assertEquals(expected, actual, "Request " + requests[i] + " is not correct");
            System.out.println("[TEST] - Client received 2f+1 ACKs and request " + actual + " has been commited");
        }

        clientLibrary.showLog();
        TimeUnit.SECONDS.sleep(20);
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
