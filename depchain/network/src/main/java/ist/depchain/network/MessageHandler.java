package ist.depchain.network;

public interface MessageHandler {
    void onReceive(String sourceId, byte[] payload);
}
