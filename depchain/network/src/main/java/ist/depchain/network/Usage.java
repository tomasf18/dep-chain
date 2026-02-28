package ist.depchain.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import ist.depchain.network.utils.Config;
import ist.depchain.network.utils.ProcessInfo;
import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;

public class Usage {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args='<configFile> <selfId>'");
            System.out.println("Example: mvn exec:java -Dexec.args='config.json p1'");
            return;
        }

        String configFile = args[0];
        String selfId = args[1];

        try {
            Config config = loadConfiguration(configFile, selfId);
            startRouter(config);
        } catch (Exception e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
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
            if (processId.equals("client")) { continue; } // skip client config if present
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

    private static void startRouter(Config config) throws Exception {

        Link fairLossLink = new UdpFairLossLink(config);
        Link stubbornLink = new StubbornLink(config, fairLossLink);
        // Link perfectLink = new PerfectLink(config, stubbornLink, fairLossLink);
        Link authenticatedPerfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink);

        // programmer decides how to handle incoming messages at app level
        MessageHandler handler = (sourceId, payload) -> {
            System.out.println("[" + config.getSelfId() + "] Received from " + sourceId + ": " + new String(payload));
        };

        authenticatedPerfectLink.registerReceiver(handler);
        authenticatedPerfectLink.start();

        System.out.println("[" + config.getSelfId() + "] Router started. Available processes: " +
                String.join(", ", config.getProcesses().keySet()));
        System.out.println("\nSystem Config: \n" + config);

        // interactive message sending
        try {
            while (true) {
                System.out.println("\n\n[" + config.getSelfId() + "] Enter message (format: <targetId> <message>), or 'exit' to quit:");
                String line = System.console().readLine();

                if (line == null || line.equalsIgnoreCase("exit")) {
                    break;
                }

                String[] parts = line.split(" ", 2);
                if (parts.length < 2) {
                    System.out.println("Invalid format. Use: <targetId> <message>");
                    continue;
                }

                String targetId = parts[0];
                String message = parts[1];

                if (!config.getProcesses().containsKey(targetId)) {
                    System.out.println("Unknown target ID: " + targetId);
                    continue;
                }

                authenticatedPerfectLink.send(targetId, message.getBytes());
                System.out.println("Sent to " + targetId + ": " + message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("[" + config.getSelfId() + "] Router stopped.");
        }
    }
}