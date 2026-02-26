package ist.depchain.network.interfaces;

public interface MessageHandler {
    void onReceive(String sourceId, byte[] payload);
}
