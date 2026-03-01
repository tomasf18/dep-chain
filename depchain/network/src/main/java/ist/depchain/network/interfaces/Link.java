package ist.depchain.network.interfaces;

import java.util.Set;
import java.util.Map;

public interface Link {
    SendHandle send(String destinationId, byte[] payload); // payload is protobuf serialized message
    public Map<String, SendHandle> broadcast(Set<String> destinationIds, byte[] payload);
    void registerReceiver(MessageHandler handler);
    void start();
    void stop();
}
