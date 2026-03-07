package ist.depchain.common.utils;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.stream.Collectors;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class Config {

    // network configuration
    private String selfId;
    private int N; // total number of processes
    private int f; // maximum number of faulty processes
    private final Map<String, ProcessInfo> processes;
    private final Map<String, ProcessInfo> clients; // derived map of client processes
    private final Map<String, ProcessInfo> blockChainServers; // derived map of blockchain server processes
    private int resendPeriodMillis; 

    // fault injection configuration
    private double dropProbability;
    private double duplicateProbability;
    private double tamperProbability;
    private int maxDelayMs;
    
    // cryptography configuration
    private String signatureAlgorithm;
    private String keysDirectory = "keystore"; // default directory for keys
    private String BLSSecretShareFileName = "bls_secret_share.key";
    private String BLSMasterPubFileName = "bls_master_pub.key";

    public Config(String selfId, int N, int f, Map<String, ProcessInfo> processes, int resendPeriodMillis,
                  double dropProbability, double duplicateProbability,
                  double tamperProbability, int maxDelayMs,
                  String signatureAlgorithm) {
        this.selfId = selfId;
        this.N = N;
        this.f = f;
        this.processes = processes;
        this.clients = processes.entrySet().stream()
                .filter(e -> e.getKey().startsWith("client"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.blockChainServers = processes.entrySet().stream()
                .filter(e -> e.getKey().startsWith("s"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.resendPeriodMillis = resendPeriodMillis;
        this.dropProbability = dropProbability;
        this.duplicateProbability = duplicateProbability;
        this.tamperProbability = tamperProbability;
        this.maxDelayMs = maxDelayMs;
        this.signatureAlgorithm = signatureAlgorithm;
    }

    public static Config loadConfiguration(String configFile, String selfId) {
        String jsonContent;
        try {
            jsonContent = Files.readString(Paths.get(configFile));
        } catch (IOException e) {
            System.out.println("[CONFIG | ERROR] - Failed to read configuration file: " + e.getMessage());
            return null;
        }
        
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
                networkConfigNode.get("N").getAsInt(),
                networkConfigNode.get("f").getAsInt(),
                processes,
                networkConfigNode.get("resendPeriodMillis").getAsInt(),
                faultConfigNode.get("dropProbability").getAsDouble(),
                faultConfigNode.get("duplicateProbability").getAsDouble(),
                faultConfigNode.get("tamperProbability").getAsDouble(),
                faultConfigNode.get("maxDelayMs").getAsInt(),
                cryptoConfigNode.get("signatureAlgorithm").getAsString()
        );
    }

    // ===== network config =====
    public String getSelfId() {
        return selfId;
    }

    // replica ids are 0-indexed strings like "s0", BLS ids are 1-indexed integers, so we add 1 here to convert
    public int selfBlsIndex() {
        return Integer.parseInt(selfId.replace("s", "")) + 1;
    }

    public int getN() {
        return N;
    }

    public int getF() {
        return f;
    }

    public ProcessInfo getSelfInfo() {
        return processes.get(selfId);
    }

    public Map<String, ProcessInfo> getProcesses() {
        return processes;
    }

    public Map<String, ProcessInfo> getClients() {
        return clients;
    }

    public Map<String, ProcessInfo> getBlockChainServers() {
        return blockChainServers;
    }

    public int getResendPeriodMillis() {
        return resendPeriodMillis;
    }

    public ProcessInfo getProcessInfo(String processId) {
        return processes.get(processId);
    }

    public String resolveProcessId(String host, int port) {
        for (Map.Entry<String, ProcessInfo> entry : processes.entrySet()) {
            ProcessInfo info = entry.getValue();
            if (hostsMatch(info.getHost(), host) && info.getPort() == port) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean hostsMatch(String configuredHost, String incomingHost) {
        try {
            InetAddress configured = InetAddress.getByName(configuredHost);
            InetAddress incoming = InetAddress.getByName(incomingHost);
            return configured.equals(incoming);
        } catch (Exception e) {
            return configuredHost.equalsIgnoreCase(incomingHost);
        }
    }

    public boolean processExists(String processId) {
        return processes.containsKey(processId);
    }

    // ===== fault injection config =====
    public double getDropProbability() {
        return dropProbability;
    }

    public double getDuplicateProbability() {
        return duplicateProbability;
    }

    public double getTamperProbability() {
        return tamperProbability;
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }

    // ===== crypto config =====
    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    public String getSelfKeysDirectory() {
        return Path.of(keysDirectory, selfId).toString();
    }

    public String getSelfPrivateKeyPathString() {
        return Path.of(getSelfKeysDirectory(), "private.pem").toString();
    }

    public String getTrustedProcessKeyPathString(String processId) {
        return Path.of(getSelfKeysDirectory(), "trusted", processId + ".pem").toString();
    }

    public String getBLSSecretSharePathString() {
        return Path.of(getSelfKeysDirectory(), BLSSecretShareFileName).toString();
    }

    public String getBLSMasterPubPathString() {
        return Path.of(getSelfKeysDirectory(), BLSMasterPubFileName).toString();
    }

    @Override
    public String toString() {
        return "Config{" +
                "\nselfId='" + selfId + '\'' +
                ", \nprocesses=" + processes.values() +
                ", \nresendPeriodMillis=" + resendPeriodMillis +
                ", \ndropProbability=" + dropProbability +
                ", \nduplicateProbability=" + duplicateProbability +
                ", \ntamperProbability=" + tamperProbability +
                ", \nmaxDelayMs=" + maxDelayMs +
                ", \nsignatureAlgorithm='" + signatureAlgorithm + '\'' +
                "\n}";
    }
}