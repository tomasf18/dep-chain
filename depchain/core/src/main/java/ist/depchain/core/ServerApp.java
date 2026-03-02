package ist.depchain.core;

import ist.depchain.common.utils.Config;

import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class ServerApp {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args='<configFile> <serverId>'");
            System.out.println("Example: mvn exec:java -Dexec.args='config.json s1'");
            return;
        }

        String configFile = args[0];
        String selfId = args[1];

        try {
            Config config = Config.loadConfiguration(configFile, selfId);
            ServerContext server = new ServerContext(config);
            HotStuffCoordinator hotStuffCoordinator = new HotStuffCoordinator(server);
            MessageHandler messageHandler = new MessageHandler(server);
            server.start();

            System.out.println("[INFO] Successfully started");

            Set<String> servers = new HashSet<>();
            servers.add("s1");
            servers.add("s2");
            servers.add("s3");
            servers.add("s4");
            Scanner in = new Scanner(System.in);
            while (true) {
                System.out.print("> ");
                String line = in.nextLine();

                if (line.equals("exit")) {
                    System.out.println("[INFO] Exiting...");
                    server.stop();
                    in.close();
                    System.out.println("[INFO] Successfully terminated");
                    return;
                } else {
                    server.getPerfectLink().send("s1", line.strip().getBytes());
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
