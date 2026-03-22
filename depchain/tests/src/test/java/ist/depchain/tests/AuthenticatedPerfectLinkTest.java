package ist.depchain.tests;

import com.google.protobuf.ByteString;
import ist.depchain.common.Envelope;
import ist.depchain.common.utils.Config;
import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.PerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.crypto.Authenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuthenticatedPerfectLinkTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private AuthenticatedPerfectLink apl0;
    private AuthenticatedPerfectLink apl1;
    private AuthenticatedPerfectLink apl2;
    private AuthenticatedPerfectLink apl3;
    private AtomicInteger counter;

    @BeforeEach
    public void setUp() throws Exception {
        // Setup of server 0
        Config config0 = Config.loadConfiguration(CONFIG_FILE, "s0");
        UdpFairLossLink udpLink0 = new UdpFairLossLink(config0);
        StubbornLink stubbornLink0 = new StubbornLink(config0, udpLink0);
        Authenticator auth0 = new Authenticator(config0);
        apl0 = new AuthenticatedPerfectLink(config0, stubbornLink0, udpLink0, auth0);

        //Setup of server 1
        Config config1 = Config.loadConfiguration(CONFIG_FILE, "s1");
        UdpFairLossLink udpLink1 = new UdpFairLossLink(config1);
        StubbornLink stubbornLink1 = new StubbornLink(config1, udpLink1);
        Authenticator auth1 = new Authenticator(config1);
        apl1 = new AuthenticatedPerfectLink(config1, stubbornLink1, udpLink1, auth1);

        Config config2 = Config.loadConfiguration(CONFIG_FILE, "s2");
        UdpFairLossLink udpLink2 = new UdpFairLossLink(config2);
        StubbornLink stubbornLink2 = new StubbornLink(config2, udpLink2);
        Authenticator auth2 = new Authenticator(config2);
        apl2 = new AuthenticatedPerfectLink(config2, stubbornLink2, udpLink2, auth2);

        Config config3 = Config.loadConfiguration(CONFIG_FILE, "s3");
        UdpFairLossLink udpLink3 = new UdpFairLossLink(config3);
        StubbornLink stubbornLink3 = new StubbornLink(config3, udpLink3);
        Authenticator auth3 = new Authenticator(config3);
        apl3 = new AuthenticatedPerfectLink(config3, stubbornLink3, udpLink3, auth3);

        counter = new AtomicInteger(0);
        apl1.registerReceiver(((sourceId, payload) -> {
            counter.incrementAndGet();
            System.out.println("[TEST] - Received payload");
        }));

        apl0.start(); apl1.start(); apl2.start(); apl3.start();
    }

    @AfterEach
    public void tearDown() {
        if (apl0 != null) apl0.stop();
        if (apl1 != null) apl1.stop();
        if (apl2 != null) apl2.stop();
        if (apl3 != null) apl3.stop();
    }

    /**
     * Test 1: Validate that the system delivers the messages exactly once, even when corruptions occurs
     * or loss of packets
     */
    @Test
    public void testCleanCommunicationAuthenticatedPerfectLink() throws Exception {
        TimeUnit.SECONDS.sleep(8);
        apl0.send("s1", "Clean message".getBytes());
        TimeUnit.SECONDS.sleep(5);
        assertEquals(1, counter.get(), "Expected: " + counter.get() + " actual: " + counter.get());
    }

    /**
     * Test 2: Validate that it receive several message and detect tampering
     */
    @Test
    public void testBurstOfMessagesAuthenticatedPerfectLink() throws Exception {
        TimeUnit.SECONDS.sleep(8);
        int totalMessages = 100;
        for (int i = 0; i < totalMessages; i++) {
            apl0.send("s1", ("Message-"+i).getBytes());
        }
        TimeUnit.SECONDS.sleep(8);
        assertEquals(totalMessages, counter.get(), "All messages should be received and authenticated");
    }

    /**
     * Test 3: Replay attack test
     */
    @Test
    public void testReplayAttack() throws Exception {
        TimeUnit.SECONDS.sleep(5);
        AtomicReference<byte[]> capturedPayload = new AtomicReference<>();

        apl1.registerReceiver(((sourceId, payload) -> {
            counter.incrementAndGet();
            if(capturedPayload.get() == null)
                capturedPayload.set(payload);
            System.out.println("[TEST] - s1 Received payload from " +  sourceId);
        }));

        apl0.send("s1", "Replay message".getBytes());
        TimeUnit.SECONDS.sleep(2);
        byte[] packetFromS0 = capturedPayload.get();

        System.out.println("[TEST] - S1 will try to resend message from S0");

        try(DatagramSocket socket = new DatagramSocket()) {
            DatagramPacket pkt = new java.net.DatagramPacket(packetFromS0, packetFromS0.length, InetAddress.getLocalHost(), 5001);
            for(int i = 0; i < 100; i++)
                socket.send(pkt);
        }

        TimeUnit.SECONDS.sleep(10);
        assertEquals(1, counter.get(), "Expected: " + 0 + " actual: " + counter.get());
    }

    /**
     * Test 4: Impersonation Attack
     */
    @Test
    public void testImpersonation() throws Exception {
        TimeUnit.SECONDS.sleep(5);

        apl1.registerReceiver(((sourceId, payload) -> {
            counter.incrementAndGet();
            System.out.println("[TEST] - s1 Received payload from " +  sourceId);
        }));

        Envelope fakeEnvelope = Envelope.newBuilder()
                .setSenderId("s2")
                .setSequenceNumber(5000)
                .setPayload(ByteString.copyFrom("Message that was not sent from s2".getBytes()))
                .build();

        byte[] attackData = fakeEnvelope.toByteArray();

        System.out.println("[TEST] - Attacker will try to impersonate message from S2");
        try(DatagramSocket socket = new DatagramSocket()) {
            DatagramPacket pkt = new DatagramPacket(attackData, attackData.length, InetAddress.getLocalHost(), 5001);
            for(int i = 0; i < 100; i++)
                socket.send(pkt);
        }

        TimeUnit.SECONDS.sleep(10);
        assertEquals(0, counter.get(), "Expected: " + 0 + " actual: " + counter.get());

    }
}
