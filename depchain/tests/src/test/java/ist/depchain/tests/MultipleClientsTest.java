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

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultipleClientsTest {
    private static final String CONFIG_FILE = "../config-test.json";

    @BeforeEach
    public void setup() {
        // Setup all replicas
        String[] replicas = {"s0", "s1", "s2", "s3"};
        for (String replica : replicas) {
            startReplica(replica);
        }

        // Wait handshake
        try { TimeUnit.SECONDS.sleep(8); } catch (Exception e) {}
    }

    @Test
    @DisplayName("Verify that multiple Clients can interact concurrently with the system")
    void testConcurrentClients() throws InterruptedException {
        Config config1 = Config.loadConfiguration(CONFIG_FILE, "client1");
        ClientContext context1 = new ClientContext(config1);
        ClientLibrary library1 = new ClientLibrary(context1);

        Config config2 = Config.loadConfiguration(CONFIG_FILE, "client2");
        ClientContext context2 = new ClientContext(config2);
        ClientLibrary library2 = new ClientLibrary(context2);

        String[] requestsC1 = {"C1-Request1", "C1-Request2", "C1-Request3", "C1-Request4"};
        String[] requestsC2 = {"C2-Request1", "C2-Request2", "C2-Request3", "C2-Request4"};

        List<Integer> commitedC1 = new ArrayList<>();
        List<Integer> commitedC2 = new ArrayList<>();

        Thread t1 = new Thread(() -> {
            for(String requestC1 : requestsC1){
                commitedC1.add(context1.getRequestId().get() + 1);
                library1.append(requestC1);
            }
        });

        Thread t2 = new Thread(() -> {
            for(String requestC2 : requestsC2){
                commitedC2.add(context2.getRequestId().get() + 1);
                library2.append(requestC2);
            }
        });

        System.out.println("[TEST] - Starting multiple Clients test");

        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        t1.join(); t2.join();

        TimeUnit.SECONDS.sleep(60);

        for(int i = 0; i < commitedC1.size(); i++) {
            boolean isCommitedC1 = context1.getPendingRequests().containsKey(commitedC1.get(i));
            assertTrue(isCommitedC1, "Not all requests from Client 1 were commited");

            boolean isCommitedC2 = context2.getPendingRequests().containsKey(commitedC2.get(i));
            assertTrue(isCommitedC2, "Not all requests from Client 2 were commited");
        }

        library1.showLog();
        library2.showLog();

        TimeUnit.SECONDS.sleep(20);

        context1.stop();
        context2.stop();
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
