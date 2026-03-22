package ist.depchain.core;

import ist.depchain.common.ClientRequest;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.common.ApplicationMessage;
import ist.depchain.network.crypto.Crypto;
import ist.depchain.network.crypto.KeyLoader;

import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandler {
    private final ServerContext serverContext;
    private final BasicHotStuffCoordinator coordinator;
    private final Map<String, Integer> lastSeenRequestId = new ConcurrentHashMap<>();

    public MessageHandler(ServerContext serverContext, BasicHotStuffCoordinator coordinator) {
        this.serverContext = serverContext;
        this.serverContext.getPerfectLink().registerReceiver(this::handleIncomingMessage);
        this.coordinator = coordinator;
    }

    private void handleIncomingMessage(String sourceId, byte[] data) {
        try {
            ApplicationMessage wrapper = ApplicationMessage.parseFrom(data);
            switch (wrapper.getContentCase()) {
                case CLIENT_REQUEST:
                    handleClientRequest(sourceId, wrapper.getClientRequest());
                    break;
                case HOTSTUFF_MESSAGE:
                    handleHotStuffMessage(sourceId, wrapper.getHotstuffMessage());
                    break;
                default:
                    System.err.println("[MESSAGE_HANDLER | ERROR] - Unknown message type from " + sourceId);
            }
        } catch (Exception e) {
            System.err.println("[MESSAGE_HANDLER | ERROR] - Failed to parse ApplicationMessage from " + sourceId);
        }
    }

    private void handleClientRequest(String sourceId, ClientRequest clientRequest) {
        String clientId = clientRequest.getClientId();
        int requestId = clientRequest.getRequestId();

        if (!verifyClientSignature(clientRequest)) {
            System.err.println("[MESSAGE_HANDLER | ERROR] - Invalid signature on client request from " + clientId);
            return;
        }

        // Reject replayed requests: request_id must be strictly greater than the last seen
        Integer lastSeen = lastSeenRequestId.get(clientId);
        if (lastSeen != null && requestId <= lastSeen) {
            System.err.println("[MESSAGE_HANDLER | WARN] - Replay detected: client " + clientId
                    + " request_id " + requestId + " <= last seen " + lastSeen);
            return;
        }
        lastSeenRequestId.put(clientId, requestId);

        System.out.println("[MESSAGE_HANDLER | INFO] - Received client request " + requestId + " from " + sourceId);
        coordinator.enqueueClientRequest(clientRequest);
    }

    private boolean verifyClientSignature(ClientRequest clientRequest) {
        String clientId = clientRequest.getClientId();
        String keyPath = serverContext.getConfig().getTrustedProcessKeyPathString(clientId);
        PublicKey clientPublicKey = KeyLoader.loadPublicKey(keyPath);

        if (clientPublicKey == null) {
            System.err.println("[MESSAGE_HANDLER | ERROR] - No public key found for " + clientId);
            return false;
        }

        // Rebuild the unsigned request to verify the signature
        ClientRequest unsigned = ClientRequest.newBuilder(clientRequest)
                .clearSignature()
                .build();

        try {
            return Crypto.verifySignature(
                    unsigned.toByteArray(),
                    clientRequest.getSignature().toByteArray(),
                    clientPublicKey,
                    serverContext.getConfig().getSignatureAlgorithm());
        } catch (Exception e) {
            System.err.println("[MESSAGE_HANDLER | ERROR] - Signature verification failed for " + clientId + ": " + e.getMessage());
            return false;
        }
    }

    private void handleHotStuffMessage(String sourceId, HotStuffMessage hotstuffMsg) {
        System.out.println("[MESSAGE_HANDLER | INFO] - Received HotStuffMessage from " + sourceId + " with type: " + hotstuffMsg.getType());
        coordinator.processMessage(sourceId, hotstuffMsg);
    }
}
