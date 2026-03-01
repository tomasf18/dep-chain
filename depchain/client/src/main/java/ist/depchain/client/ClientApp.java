package ist.depchain.client;

import ist.depchain.common.utils.Config;
import java.util.Scanner;

public class ClientApp {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"<configFile>\"");
            System.out.println("Example: mvn exec:java -Dexec.args=\"config.json\"");
            return;
        }

        String configFile = args[0];
        String clientId = "client";
        try {
            Config config = Config.loadConfiguration(configFile, clientId);
            ClientContext client = new ClientContext(config);
            ClientLibrary clientLib = new ClientLibrary(client);
            client.start();

            System.out.println("[INFO] Successfully started");
            System.out.println("[INFO] Write message to append or 'exit' to terminate");

            Scanner in = new Scanner(System.in);
            while (true) {
                System.out.println("\n === === === === === === === === ===");
                System.out.println("[" + config.getSelfId() + "] Select an action (or 'exit' to quit):");
                System.out.println("Enter '1' to: Append to log");
                System.out.println("=== === === === === === === === ===");
                System.out.print("> \n");
                String line = in.nextLine();

                switch (line) {
                    case "1":
                        System.out.print("Enter message to append: ");
                        String message = in.nextLine();
                        clientLib.append(message);
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
