package ist.depchain.tests;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultipleClientsTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private ClientContext clientContext1;
    private ClientContext clientContext2;
    private ClientLibrary clientLibrary1;
    private ClientLibrary clientLibrary2;

    @BeforeEach
    public void setup() throws InterruptedException{
        // Setup all replicas
        String[] replicas = {"s0", "s1", "s2", "s3"};
        for (String replica : replicas) {startReplica(replica);}
        // Wait handshake
        TimeUnit.SECONDS.sleep(8);
    }

    @Test
    @DisplayName("Verify that multiple Clients can interact concurrently with the system")
    void testConcurrentClients() throws InterruptedException {
        Config config1 = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext1 = new ClientContext(config1);
        clientLibrary1 = new ClientLibrary(clientContext1);
        clientContext1.start();

        Config config2 = Config.loadConfiguration(CONFIG_FILE, "client2");
        clientContext2 = new ClientContext(config2);
        clientLibrary2 = new ClientLibrary(clientContext2);
        clientContext2.start();

        String[] requestsC1 = {"C1-Request1", "C1-Request2", "C1-Request3", "C1-Request4"};
        String[] requestsC2 = {"C2-Request1", "C2-Request2", "C2-Request3", "C2-Request4"};

        TimeUnit.SECONDS.sleep(30);
        Thread t1 = new Thread(() -> {for(String requestC1 : requestsC1){clientLibrary1.append(requestC1);}});
        Thread t2 = new Thread(() -> {for(String requestC2 : requestsC2){clientLibrary2.append(requestC2);}});

        System.out.println("[TEST] - Starting multiple Clients test");

        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        t1.join(); t2.join();

        int time = 300;
        while (time > 0 && (clientContext1.getCommitedLog().size() < requestsC1.length || clientContext2.getCommitedLog().size() < requestsC2.length)) {
            TimeUnit.SECONDS.sleep(1);
            time --;
        }

        // Final validation
        List<String> committedLog1 = clientContext1.getCommitedLog();
        List<String> committedLog2 = clientContext2.getCommitedLog();

        assertEquals(committedLog1.size(), requestsC1.length, "Client1 missing some commits");
        assertEquals(committedLog2.size(), requestsC2.length, "Client2 missing some commits");

        for(String requestC1 : requestsC1){assertTrue(committedLog1.contains(requestC1), "Request " + requestC1 +" is not correct");}
        for(String requestC2 : requestsC2){assertTrue(committedLog2.contains(requestC2), "Request " + requestC2 +" is not correct");}

        clientLibrary1.showLog(); clientLibrary2.showLog();
        TimeUnit.SECONDS.sleep(120);
        clientContext1.stop(); clientContext2.stop();
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
