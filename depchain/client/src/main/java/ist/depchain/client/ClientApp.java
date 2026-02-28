package ist.depchain.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import ist.depchain.network.utils.Config;
import ist.depchain.network.utils.ProcessInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Hello world!
 */
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
            /* EXTRACT PROCESS CONFIGS */
            Config config = loadConfiguration(configFile, clientId);
            DepChainClient client = new DepChainClient(config);
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
            System.out.println("[ERROR] Failed to load config file: " + e.getMessage());
        }
    }

    private static Config loadConfiguration(String configFile, String selfId) throws IOException {
        String jsonContent = Files.readString(Paths.get(configFile));
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(jsonContent, JsonObject.class);
        JsonObject faultConfigNode = root.getAsJsonObject("faultConfig");
        JsonObject cryptoConfigNode = root.getAsJsonObject("cryptoConfig");
        JsonObject networkConfigNode = root.getAsJsonObject("networkConfig");
        JsonObject processesNode = networkConfigNode.getAsJsonObject("processes");

        Map<String, ProcessInfo> processes = new HashMap<>();
        for (String processId : processesNode.keySet()) {
            JsonObject processJson = processesNode.getAsJsonObject(processId);
            ProcessInfo info = new ProcessInfo(
                    processId,
                    processJson.get("host").getAsString(),
                    processJson.get("port").getAsInt()
            );
            processes.put(processId, info);
        }

        return new Config(
                selfId,
                processes,
                networkConfigNode.get("resendPeriodMillis").getAsInt(),
                faultConfigNode.get("dropProbability").getAsDouble(),
                faultConfigNode.get("duplicateProbability").getAsDouble(),
                faultConfigNode.get("tamperProbability").getAsDouble(),
                faultConfigNode.get("maxDelayMs").getAsInt(),
                cryptoConfigNode.get("signatureAlgorithm").getAsString()
        );
    }
}
