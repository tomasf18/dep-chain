package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

public class ServerApp {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args='<configFile> <serverId>'");
            System.out.println("Example: mvn exec:java -Dexec.args='../config-dev.json s1'");
            return;
        }

        String configFile = args[0];
        String selfId = args[1];
        
        Config config = Config.loadConfiguration(configFile, selfId);
        if (config == null) {
            return;
        }
        ServerContext server = new ServerContext(config);
        BasicHotStuffCoordinator hotStuffCoordinator = new BasicHotStuffCoordinator(server);
        new MessageHandler(server, hotStuffCoordinator);
        try {
            server.start();
        } catch (Exception e) {
            System.err.println("[SERVER_APP | ERROR] - Error while starting server: " + e.getMessage());
            return;
        }
        hotStuffCoordinator.start();
        
        System.out.println("[SERVER_APP | INFO] Successfully started");
    }
}
