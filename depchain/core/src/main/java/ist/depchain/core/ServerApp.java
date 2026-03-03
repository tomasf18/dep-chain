package ist.depchain.core;

import ist.depchain.common.utils.Config;

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
            MessageHandler messageHandler = new MessageHandler(server, hotStuffCoordinator);
            server.start();
        } catch (Exception e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
