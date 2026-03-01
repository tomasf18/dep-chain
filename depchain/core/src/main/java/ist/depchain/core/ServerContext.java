package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.network.abstractions.PerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.crypto.Authenticator;

public class ServerContext {
    private final Config config;

    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private PerfectLink perfectLink; // pass authenticator to perfect link for activating APL features

    public ServerContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
    }

    public void start() throws Exception {
        perfectLink = new PerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));

        System.out.println("[" + config.getSelfId() + "] Router started. Available processes: " +
                String.join(", ", config.getProcesses().keySet()));
        System.out.println("\nSystem Config: \n" + config);

        // interactive message sending
        try {
            while (true) {
                System.out.println("\n\n[" + config.getSelfId() + "] Enter message (format: <targetId> <message>), or 'exit' to quit:");
                String line = System.console().readLine();

                if (line == null || line.equalsIgnoreCase("exit")) {
                    break;
                }

                String[] parts = line.split(" ", 2);
                if (parts.length < 2) {
                    System.out.println("Invalid format. Use: <targetId> <message>");
                    continue;
                }

                String targetId = parts[0];
                String message = parts[1];

                if (!config.getProcesses().containsKey(targetId)) {
                    System.out.println("Unknown target ID: " + targetId);
                    continue;
                }

                perfectLink.send(targetId, message.getBytes());
                System.out.println("Sent to " + targetId + ": " + message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("[" + config.getSelfId() + "] Router stopped.");
        }
    }
}