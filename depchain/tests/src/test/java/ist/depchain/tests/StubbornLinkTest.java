package ist.depchain.tests;

import ist.depchain.common.utils.Config;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.interfaces.SendHandle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class StubbornLinkTest {
    private static final String CONFIG_FILE = "../config-test.json";
    @Test
    public void testStubbornLinkSendAndReceive() throws Exception {
        Config config1 = Config.loadConfiguration(CONFIG_FILE, "s0");
        Config config2 = Config.loadConfiguration(CONFIG_FILE, "s1");

        UdpFairLossLink udp1 = new UdpFairLossLink(config1);
        UdpFairLossLink udp2 = new UdpFairLossLink(config2);

        StubbornLink stubbornLink1 = new StubbornLink(config1, udp1);
        StubbornLink stubbornLink2 = new StubbornLink(config2, udp2);

        List<String> receivedMessages = new ArrayList<>();

        stubbornLink2.registerReceiver(((sourceId, payload) -> {
            String message = new String(payload);
            synchronized (receivedMessages) {
                receivedMessages.add(message);
                System.out.println("[TEST] - Server s1 received: " + message + " from " + sourceId);
            }
        }));
        stubbornLink1.start(); stubbornLink2.start();

        byte[] payload = "Super Secret from StubbornLink".getBytes();
        System.out.println("[TEST] - s0 sending message via stubborn link ...");
        SendHandle handle = stubbornLink1.send("s1", payload);

        TimeUnit.SECONDS.sleep(5);
        System.out.println("[TEST] - s1 receiving ...");

        handle.cancel();
        System.out.println("[TEST] - s1 cancelled StubbornLink retransmission ...");

        stubbornLink1.stop(); stubbornLink2.stop();

        synchronized (receivedMessages) {
            System.out.println("[TEST] - Total messages: " + receivedMessages.size());
            assertTrue(receivedMessages.size() > 1, "Stubborn failed");
            assertTrue(receivedMessages.contains("Super Secret from StubbornLink"), "Original message wasn't delivered");
        }
    }
}
