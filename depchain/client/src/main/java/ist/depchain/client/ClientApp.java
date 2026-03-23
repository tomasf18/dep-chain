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

            System.out.println("[INFO] Successfully started");

            Scanner in = new Scanner(System.in);
            while (true) {
                System.out.println("\n=== DepChain Client ===");
                System.out.println("1 - Submit native transfer");
                System.out.println("exit - Quit");
                System.out.print("> ");
                String line = in.nextLine();

                switch (line) {
                    case "1":
                        System.out.print("Destination address (0x...): ");
                        String to = in.nextLine();

                        System.out.print("Value: ");
                        java.math.BigInteger value = new java.math.BigInteger(in.nextLine());

                        System.out.print("Gas price: ");
                        java.math.BigInteger gasPrice = new java.math.BigInteger(in.nextLine());

                        System.out.print("Gas limit: ");
                        java.math.BigInteger gasLimit = new java.math.BigInteger(in.nextLine());

                        System.out.print("Nonce: ");
                        long nonce = Long.parseLong(in.nextLine());

                        clientLib.submitNativeTransfer(to, value, gasPrice, gasLimit, nonce);
                        break;

                    case "exit":
                        System.out.println("[INFO] Exiting...");
                        client.stop();
                        in.close();
                        return;

                    default:
                        System.out.println("Unknown option");
                }
            }
        }
        catch(Exception e){
            System.out.println("[ERROR] Failed to load config file: " + e.getMessage());
        }
    }
}
