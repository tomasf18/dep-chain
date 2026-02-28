package ist.depchain.network.utils;

public class ProcessInfo {
    private String id;
    private String host;
    private int port;

    public ProcessInfo(String id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
    }

    public String getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return "ProcessInfo{id='" + id + "', host='" + host + "', port=" + port + "}";
    }
}
