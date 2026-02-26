package ist.depchain.network.interfaces;

public interface Link {
    SendHandle send(String destinationId, byte[] payload); // payload is protobuf serialized message
    void registerReceiver(MessageHandler handler);
    void start();
    void stop();
}
