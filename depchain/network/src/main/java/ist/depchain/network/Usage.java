package ist.depchain.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import ist.depchain.network.utils.ArtificialFaultConfig;
import ist.depchain.network.utils.ProcessConfig;
import ist.depchain.network.utils.ProcessInfo;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;

public class Usage {
    private static class NetworkConfig {
        String selfId;
        ArtificialFaultConfig faultConfig;
        Map<String, ProcessInfo> processes;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"<configFile> <selfId>\"");
            System.out.println("Example: mvn exec:java -Dexec.args=\"config.json process1\"");
            return;
        }

        String configFile = args[0];
        String selfId = args[1];

        try {
            NetworkConfig config = loadConfiguration(configFile, selfId);
            startRouter(config);
        } catch (IOException e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static NetworkConfig loadConfiguration(String configFile, String selfId) throws IOException {
        String jsonContent = Files.readString(Paths.get(configFile));
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(jsonContent, JsonObject.class);

        NetworkConfig config = new NetworkConfig();
        config.selfId = selfId;

        // parse fault configuration
        JsonObject faultJson = root.getAsJsonObject("faultConfig");
        config.faultConfig = new ArtificialFaultConfig(
            faultJson.get("packetDropRate").getAsDouble(),
            faultJson.get("packetDuplicationRate").getAsDouble(),
            faultJson.get("packetDelayMs").getAsInt()
        );

        // parse processes
        config.processes = new HashMap<>();
        JsonObject processesJson = root.getAsJsonObject("processes");
        for (String processId : processesJson.keySet()) {
            JsonObject processJson = processesJson.getAsJsonObject(processId);
            ProcessInfo info = new ProcessInfo(
                processId,
                processJson.get("host").getAsString(),
                processJson.get("port").getAsInt()
            );
            config.processes.put(processId, info);
        }

        if (!config.processes.containsKey(selfId)) {
            throw new IllegalArgumentException("Self ID '" + selfId + "' not found in configuration");
        }

        return config;
    }

    private static void startRouter(NetworkConfig config) {
        ProcessInfo selfInfo = config.processes.get(config.selfId);
        ProcessConfig processConfig = new ProcessConfig(config.processes);

        Link fairLossLink = new UdpFairLossLink(selfInfo, processConfig, config.faultConfig);
        Link stubbornLink = new StubbornLink(fairLossLink, 1000);
        Link perfectLink = new PerfectLink(config.selfId, stubbornLink, fairLossLink);

        MessageHandler handler = (sourceId, payload) -> {
            System.out.println("[" + config.selfId + "] Received from " + sourceId + ": " + new String(payload));
        };

        perfectLink.registerReceiver(handler);
        perfectLink.start();

        System.out.println("[" + config.selfId + "] Router started. Available processes: " + 
            String.join(", ", config.processes.keySet()));

        // interactive message sending
        try {
            while (true) {
                System.out.println("\n[" + config.selfId + "] Enter message (format: <targetId> <message>), or 'exit' to quit:");
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

                if (!config.processes.containsKey(targetId)) {
                    System.out.println("Unknown target ID: " + targetId);
                    continue;
                }

                perfectLink.send(targetId, message.getBytes());
                System.out.println("Sent to " + targetId + ": " + message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("[" + config.selfId + "] Router stopped.");
        }
    }
}