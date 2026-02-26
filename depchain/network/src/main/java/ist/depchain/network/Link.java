package ist.depchain.network;

public interface Link {
    void send(String destinationId, byte[] payload); // payload is protobuf serialized message
    void registerReceiver(MessageHandler handler);
    void start();
    void stop();
}
