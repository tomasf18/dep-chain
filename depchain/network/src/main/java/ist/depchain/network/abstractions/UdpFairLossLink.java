package ist.depchain.network.abstractions;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.ProcessInfo;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

public class UdpFairLossLink implements Link {

    private final DatagramSocket socket;
    private final Config config;
    private MessageHandler handler;
    private volatile boolean running = false;

    public UdpFairLossLink(Config config) {
        this.config = config;
        try {
            this.socket = new DatagramSocket(config.getSelfInfo().getPort());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket", e);
        }
    }

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        if (Math.random() < config.getDropProbability()) { return null; } // drop the packet
        
        ProcessInfo destInfo = config.getProcesses().get(destinationId);
        if (destInfo == null) {
            throw new IllegalArgumentException("Unknown destination: " + destinationId);
        } 
        try {
            if (config.getMaxDelayMs() > 0) { Thread.sleep((long)(Math.random() * config.getMaxDelayMs())); } // random delay

            // tamper with the payload
            byte[] dataToSend = payload;
            if (Math.random() < config.getTamperProbability()) {
                dataToSend = Arrays.copyOf(payload, payload.length);
                dataToSend[(int)(Math.random() * dataToSend.length)] ^= (byte) 0xFF; // flip all bits of a random byte
                System.out.println("FairLossLink: TAMPERED packet to " + destinationId);
            }

            DatagramPacket packet = new DatagramPacket(dataToSend, dataToSend.length, InetAddress.getByName(destInfo.getHost()), destInfo.getPort());
            socket.send(packet);

            if (Math.random() < config.getDuplicateProbability()) { socket.send(packet); } // send duplicate

        } catch (Exception e) {
            throw new RuntimeException("Failed to send packet", e);
        }

        return null;
    }

    @Override
    public Map<String, SendHandle> broadcast(List<String> destinationIds, byte[] payload) {
        Map<String, SendHandle> handlesPerDestination = new HashMap<>();
        for (String dest : destinationIds) {
            SendHandle handle = send(dest, payload);
            handlesPerDestination.put(dest, handle);
        }
        return handlesPerDestination;
    }

    @Override
    public void registerReceiver(MessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public void start() {
        running = true;
        new Thread(this::receiveLoop).start();

    }

    private void receiveLoop() {
        byte[] buffer = new byte[65536];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                byte[] payload = Arrays.copyOf(packet.getData(), packet.getLength());
                String sourceId = config.resolveProcessId(packet.getAddress().getHostAddress(), packet.getPort());
                if (sourceId != null && handler != null) {
                    handler.onReceive(sourceId, payload);
                }
            } catch (Exception e) {
                System.err.println("Failed to receive packet: " + e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        socket.close();
    }
    
}
