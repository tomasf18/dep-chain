package ist.depchain.network;

import java.io.File;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"<selfId> <selfPort> <otherId> <otherPort>\"");
            return;
        }

        String selfId = args[0];
        String selfHost = "localhost";
        int selfPort = Integer.parseInt(args[1]);
        String otherId = args[2];
        String otherHost = "localhost";
        int otherPort = Integer.parseInt(args[3]);

        ProcessInfo selfInfo = new ProcessInfo(selfId, selfHost, selfPort);
        ProcessConfig config = new ProcessConfig(Map.of(
            selfId, selfInfo,
            otherId, new ProcessInfo(otherId, otherHost, otherPort)
        ));

        ArtificialFaultConfig faultConfig = new ArtificialFaultConfig(0, 0, 0);
        Link link = new UdpFairLossLink(selfInfo, config, faultConfig);
        MessageHandler handler = (sourceId, payload) -> {
            System.out.println("Received from " + sourceId + ": " + new String(payload));
        };
        link.registerReceiver(handler);
        link.start();

        // prompt to send messages
        try {
            while (true) {
                System.out.println("Type a message to send to " + otherId + ":");
                String line = System.console().readLine();
                if (line == null || line.equalsIgnoreCase("exit")) {
                    break;
                }
                link.send(otherId, line.getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
