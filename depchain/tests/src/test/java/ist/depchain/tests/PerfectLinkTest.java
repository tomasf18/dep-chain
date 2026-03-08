package ist.depchain.tests;

import ist.depchain.common.utils.Config;
import ist.depchain.network.abstractions.PerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerfectLinkTest {
    private static final String CONFIG_FILE = "../config-test.json";

    @Test
    public void testPerfectLinkExactlyOneAck() throws Exception {
        Config config1 = Config.loadConfiguration(CONFIG_FILE, "s0");
        Config config2 = Config.loadConfiguration(CONFIG_FILE, "s1");

        UdpFairLossLink fairLossLink1 = new UdpFairLossLink(config1);
        UdpFairLossLink fairLossLink2 = new UdpFairLossLink(config2);

        StubbornLink stubbornLink1 = new StubbornLink(config1, fairLossLink1);
        StubbornLink stubbornLink2 = new StubbornLink(config2, fairLossLink2);

        PerfectLink perfectLink1 = new PerfectLink(config1, stubbornLink1, fairLossLink1);
        PerfectLink perfectLink2 = new PerfectLink(config2, stubbornLink2, fairLossLink2);

        AtomicInteger counter = new AtomicInteger(0);
        CopyOnWriteArrayList<String> receivedMessages = new CopyOnWriteArrayList<>();

        perfectLink2.registerReceiver(((sourceId, payload) -> {
            receivedMessages.add(new String(payload));
            counter.incrementAndGet();
            System.out.println("[TEST] - PerfectLink received: " + new String(payload));
        }));

        perfectLink1.start(); perfectLink2.start();

        byte[] payload = "No one can know this secret, ok PerfectLink?".getBytes();
        System.out.println("[TEST] - Sending data from s0 to s1 via PerfectLink ...");
        perfectLink1.send("s1", payload);

        TimeUnit.SECONDS.sleep(3);

        assertEquals(1, receivedMessages.size(), "System should receive message exactly once, independently of the retransmissions of StubbornLink");

        assertTrue(perfectLink1.getOutgoingMessagesSeqNumbers().get("s1").get() >= 1);

        System.out.println("[TEST] - Terminating PerfectLink ...");
        perfectLink1.stop(); perfectLink2.stop();
    }
}
