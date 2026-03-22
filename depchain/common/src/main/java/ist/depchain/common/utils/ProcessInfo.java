package ist.depchain.common.utils;

public class ProcessInfo {
    private String id;
    private String host;
    private int port;
    private String role; // "client" or "server"

    public ProcessInfo(String id, String host, int port, String role) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.role = role;
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

    public String getRole() {
        return role;
    }

    public boolean isClient() {
        return "client".equals(role);
    }

    public boolean isServer() {
        return "server".equals(role);
    }

    @Override
    public String toString() {
        return "ProcessInfo{id='" + id + "', host='" + host + "', port=" + port + ", role='" + role + "'}";
    }
}
