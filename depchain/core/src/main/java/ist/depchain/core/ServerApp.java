package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.core.byzantine.ByzantineCoordinator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerApp {
    // Registry for test inspection - maps serverId to its coordinator
    private static final Map<String, BasicHotStuffCoordinator> coordinators = new ConcurrentHashMap<>();

    public static BasicHotStuffCoordinator getCoordinator(String serverId) {
        return coordinators.get(serverId);
    }

    public static void stopAll() {
        for (Map.Entry<String, BasicHotStuffCoordinator> entry : coordinators.entrySet()) {
            entry.getValue().stop();
            try { entry.getValue().getServerContext().stop(); } catch (Exception ignored) {}
        }
        coordinators.clear();
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args='<configFile> <serverId> <byzantine_flag> <attack_type>'");
            System.out.println("Example: mvn exec:java -Dexec.args='../config-dev.json s1 true EQUIVOCATE'");
            return;
        }

        String configFile = args[0];
        String selfId = args[1];
        boolean byzantineFlag = args.length > 2 && args[2].equalsIgnoreCase("true");
        String attackType = args.length > 3 ? args[3].toUpperCase() : "SILENT";
        
        Config config = Config.loadConfiguration(configFile, selfId);
        if (config == null) {
            return;
        }
        ServerContext server = new ServerContext(config);
        BasicHotStuffCoordinator hotStuffCoordinator;
        if (byzantineFlag) {
            ist.depchain.core.byzantine.ByzantineCoordinator byzantineCoordinator = new ByzantineCoordinator(server);
            try {
                byzantineCoordinator.setAttack(ByzantineCoordinator.AttackType.valueOf(attackType));
            }catch (IllegalArgumentException e) {
                byzantineCoordinator.setAttack(ByzantineCoordinator.AttackType.SILENT);
            }
            hotStuffCoordinator = byzantineCoordinator;
        }
        else {hotStuffCoordinator = new BasicHotStuffCoordinator(server, byzantineFlag);}
        coordinators.put(selfId, hotStuffCoordinator);
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
