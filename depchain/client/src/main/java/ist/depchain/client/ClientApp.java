package ist.depchain.client;

import ist.depchain.common.utils.Config;
import java.util.Scanner;

public class ClientApp {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"<configFile> <clientId>\"");
            System.out.println("Example: mvn exec:java -Dexec.args=\"config-dev.json client1\"");
            return;
        }

        String configFile = args[0];
        String clientId = args[1];
        
        try {
            Config config = Config.loadConfiguration(configFile, clientId);
            ClientContext client = new ClientContext(config);
            ClientLibrary clientLib = new ClientLibrary(client);
            client.start();

            System.out.println("[INFO] Waiting for authenticated sessions with servers...");
            client.waitForHandshakes();
            System.out.println("[INFO] Successfully started");

            Scanner in = new Scanner(System.in);
            while (true) {
                System.out.println("\n === === === === === === === === ===");
                System.out.println("  [" + config.getSelfId() + "] Select an action (or 'exit' to quit):");
                System.out.println("  Enter '1' to: Append to log");
                System.out.println("  Enter '2' to: View log");
                System.out.println(" === === === === === === === === ===");
                System.out.print("> ");
                String line = in.nextLine();

                switch (line) {
                    case "1":
                        System.out.print("Enter message to append: ");
                        String message = in.nextLine();
                        clientLib.append(message);
                        break;
                    case "2":
                        clientLib.showLog();
                        break;
                    case "exit":
                        System.out.println("[INFO] Exiting...");
                        client.stop();
                        in.close();
                        System.out.println("[INFO] Successfully terminated");
                        return;
                
                    default:
                        break;
                }
            }
        }
        catch(Exception e){
            System.out.println("[ERROR] Failed to load config file: " + e.getMessage());
        }
    }
}
