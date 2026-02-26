package ist.depchain.network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

public class UdpFairLossLink implements Link {

    private final DatagramSocket socket;
    private final ProcessConfig config;
    private MessageHandler handler;
    private volatile boolean running = false;

    // artificially introduce faults
    private final ArtificialFaultConfig faultConfig;

    public UdpFairLossLink(ProcessInfo selfInfo, ProcessConfig config, ArtificialFaultConfig faultConfig) {
        this.config = config;
        this.faultConfig = faultConfig;
        try {
            this.socket = new DatagramSocket(selfInfo.getPort());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create socket", e);
        }
    }


    @Override
    public void send(String destinationId, byte[] payload) {
        if (Math.random() < faultConfig.getDropProbability()) { return; } // drop the packet
        
        ProcessInfo destInfo = config.getProcesses().get(destinationId);
        if (destInfo == null) {
            throw new IllegalArgumentException("Unknown destination: " + destinationId);
        } 
        try {
            DatagramPacket packet = new DatagramPacket(payload, payload.length, InetAddress.getByName(destInfo.getHost()), destInfo.getPort());
            socket.send(packet);

            if (Math.random() < faultConfig.getDuplicateProbability()) { socket.send(packet); } // send duplicate
            if (faultConfig.getMaxDelayMs() > 0) { Thread.sleep((long)(Math.random() * faultConfig.getMaxDelayMs())); } // random delay

        } catch (Exception e) {
            throw new RuntimeException("Failed to send packet", e);
        }
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
                String sourceId = config.resolveId(packet.getAddress().getHostAddress(), packet.getPort());
                if (sourceId != null && handler != null) {
                    handler.onReceive(sourceId, payload);
                }
            } catch (Exception e) {
                // Log and ignore
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        socket.close();
    }
    
}
