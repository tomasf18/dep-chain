package ist.depchain.network;

import java.util.Map;

public class ProcessConfig {
    Map<String, ProcessInfo> processes; // process id -> ProcessInfo

    public String resolveId(String host, int port) {
        for (Map.Entry<String, ProcessInfo> entry : processes.entrySet()) {
            ProcessInfo info = entry.getValue();
            if (info.host.equals(host) && info.port == port) {
                return entry.getKey();
            }
        }
        return null; 
    }
}
