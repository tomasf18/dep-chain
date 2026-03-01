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
                System.out.print(">> ");
                String line = in.nextLine();

                if (line.equalsIgnoreCase("exit")) {break;}
                if(!line.isBlank()){clientLib.append(line);}
            }

            client.stop();
            in.close();
            System.out.println("[INFO] Successfully terminated");
        }
        catch(Exception e){
            System.out.println("[ERROR] Failed to load config file: " + e.getMessage());
        }
    }
}
