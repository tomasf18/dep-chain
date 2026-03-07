package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.core.byzantine.ByzantineCoordinator;

public class ServerApp {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args='<configFile> <serverId> <byzantine_flag>'");
            System.out.println("Example: mvn exec:java -Dexec.args='../config-dev.json s1 [isByzantine]'");
            return;
        }

        String configFile = args[0];
        String selfId = args[1];
        boolean byzantineFlag = args.length > 2 && args[2].equalsIgnoreCase("true");
        
        Config config = Config.loadConfiguration(configFile, selfId);
        if (config == null) {
            return;
        }
        ServerContext server = new ServerContext(config);
        BasicHotStuffCoordinator hotStuffCoordinator;
        if (byzantineFlag) { hotStuffCoordinator = new ByzantineCoordinator(server);}
        else {hotStuffCoordinator = new BasicHotStuffCoordinator(server, byzantineFlag);}
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
