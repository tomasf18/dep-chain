package ist.depchain.common.utils;

public class ProcessInfo {
    private String id;
    private String host;
    private int port;
    private String role; // "client" or "server"
    private String address; // blockchain address for clients and servers

    public ProcessInfo(String id, String host, int port, String role, String address) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.role = role;
        this.address = address;
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

    public String getAddress() {
        return address;
    }

    public boolean isClient() {
        return "client".equals(role);
    }

    public boolean isServer() {
        return "server".equals(role);
    }

    @Override
    public String toString() {
        return "ProcessInfo{id='" + id + "', host='" + host + "', port=" + port + ", role='" + role + "', address='" + address + "'}";
    }
}
