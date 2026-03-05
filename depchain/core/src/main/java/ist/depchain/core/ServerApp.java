package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.core.hotstuff.HotStuffCoordinator;

import java.util.Scanner;
import java.util.Set;

public class ServerApp {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args='<configFile> <serverId>'");
            System.out.println("Example: mvn exec:java -Dexec.args='../config.json s1'");
            return;
        }

        String configFile = args[0];
        String selfId = args[1];
        
        Config config = Config.loadConfiguration(configFile, selfId);
        if (config == null) {
            return;
        }
        ServerContext server = new ServerContext(config);
        HotStuffCoordinator hotStuffCoordinator = new HotStuffCoordinator(server);
        new MessageHandler(server, hotStuffCoordinator);
        try {
            server.start();
        } catch (Exception e) {
            System.err.println("Error while starting server: " + e.getMessage());
            return;
        }
        System.out.println("[INFO] Successfully started");

        interactiveMode(config, server);

    }


    private static void interactiveMode(Config config, ServerContext server) {
        System.out.println("[INFO] Input format: <destination> <message>  (use any non-'sX' destination to broadcast)");

        Set<String> allServers = config.getBlockChainServers().keySet();
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String line = in.nextLine().strip();

            if (line.isEmpty()) continue;

            if (line.equals("exit")) {
                System.out.println("[INFO] Exiting...");
                try {
                    server.stop();
                } catch (Exception e) {
                    System.err.println("Error while stopping server: " + e.getMessage());
                    e.printStackTrace();
                }
                in.close();
                System.out.println("[INFO] Successfully terminated");
                return;
            }

            int space = line.indexOf(' ');
            String destination = (space != -1) ? line.substring(0, space) : line;

            if (destination.matches("s[1-4]")) {
                byte[] payload = line.substring(space + 1).getBytes();
                server.getPerfectLink().send(destination, payload);
            } else {
                server.getPerfectLink().broadcast(allServers, line.getBytes());
            }
        }
    }
}
