package ist.depchain.network;

import java.net.InetAddress;
import java.util.Map;

public class ProcessConfig {
    private Map<String, ProcessInfo> processes; // process id -> ProcessInfo

    public ProcessConfig(Map<String, ProcessInfo> processes) {
        this.processes = processes;
    }

    public Map<String, ProcessInfo> getProcesses() {
        return processes;
    }

    public String resolveId(String host, int port) {
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
}
