package ist.depchain.tests;

import ist.depchain.common.utils.Config;
import ist.depchain.network.abstractions.UdpFairLossLink;
import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class UDPFairLossTest {
    private static final String CONFIG_FILE = "../config-test.json";

    @Test
    public void testUDPFairLossSendAndReceive() throws SocketException, InterruptedException {
        Config config1 = Config.loadConfiguration(CONFIG_FILE, "s0");
        Config config2 = Config.loadConfiguration(CONFIG_FILE, "s1");

        UdpFairLossLink link1 = new UdpFairLossLink(config1);
        UdpFairLossLink link2 = new UdpFairLossLink(config2);

        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        link2.registerReceiver(((sourceId, payload) -> {
            receivedData.set(payload);
        }));

        link1.start(); link2.start();

        byte[] originalData = "Hello UDPFairLoss".getBytes();
        link1.send("s1", originalData);
        TimeUnit.SECONDS.sleep(5);
        assertArrayEquals(originalData, receivedData.get());

        link1.stop(); link2.stop();
    }
}
