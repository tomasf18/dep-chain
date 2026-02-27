package ist.depchain.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import ist.depchain.network.utils.ArtificialFaultConfig;
import ist.depchain.network.utils.ProcessConfig;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Hello world!
 */
public class ClientApp {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"<configFile> <clientId>\"");
            System.out.println("Example: mvn exec:java -Dexec.args=\"config.json client1\"");
            return;
        }

        String configFile = args[0];
        String clientId = args[1];
        try {
            /* EXTRACT PROCESS CONFIGS */
            String content = Files.readString(Paths.get(configFile));
            JsonObject json = new Gson().fromJson(content, JsonObject.class);
            ProcessConfig config =  new Gson().fromJson(json, ProcessConfig.class);

            ArtificialFaultConfig faultConfig = new Gson().fromJson(json.get("faultConfig"), ArtificialFaultConfig.class);

            DepChainClient client = new DepChainClient(clientId, config, faultConfig);
            client.start();

            System.out.println("[INFO] Successfully started");
            System.out.println("[INFO] Write message to append or 'exit' to terminate");

            Scanner in = new Scanner(System.in);
            while (true) {
                System.out.println(">> ");
                String line = in.nextLine();

                if (line.equalsIgnoreCase("exit")) {break;}
                if(!line.isBlank()){client.append(line);}
            }

            client.stop();
            in.close();
            System.out.println("[INFO] Successfully terminated");
        }
        catch(Exception e){
            System.out.println("Failed to load config file: " + e.getMessage());
        }
    }
}
