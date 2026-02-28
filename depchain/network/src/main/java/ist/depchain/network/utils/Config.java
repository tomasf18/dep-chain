package ist.depchain.network.utils;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;

public class Config {

    // network configuration
    private String selfId;
    private Map<String, ProcessInfo> processes;
    private int resendPeriodMillis; 

    // fault injection configuration
    private double dropProbability;
    private double duplicateProbability;
    private double tamperProbability;
    private int maxDelayMs;
    
    // cryptography configuration
    private String signatureAlgorithm;
    private String keysDirectory = "keystore"; // default directory for keys

    public Config(String selfId, Map<String, ProcessInfo> processes, int resendPeriodMillis,
                  double dropProbability, double duplicateProbability,
                  double tamperProbability, int maxDelayMs,
                  String signatureAlgorithm) {
        this.selfId = selfId;
        this.processes = processes;
        this.resendPeriodMillis = resendPeriodMillis;
        this.dropProbability = dropProbability;
        this.duplicateProbability = duplicateProbability;
        this.tamperProbability = tamperProbability;
        this.maxDelayMs = maxDelayMs;
        this.signatureAlgorithm = signatureAlgorithm;
    }

    // ===== network config =====
    public String getSelfId() {
        return selfId;
    }

    public ProcessInfo getSelfInfo() {
        return processes.get(selfId);
    }

    public Map<String, ProcessInfo> getProcesses() {
        return processes;
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
        return Path.of(keysDirectory, selfId, "private.pem").toString();
    }

    public String getTrustedProcessKeyPathString(String processId) {
        return Path.of(keysDirectory, selfId, "trusted", processId + ".pem").toString();
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