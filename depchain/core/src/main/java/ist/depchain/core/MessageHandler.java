package ist.depchain.core;

import ist.depchain.common.Transaction;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.common.ApplicationMessage;
import ist.depchain.network.crypto.Crypto;
import ist.depchain.network.crypto.KeyLoader;
import ist.depchain.core.blockchain.TransactionValidator;

import org.hyperledger.besu.datatypes.Address;

import java.security.PublicKey;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandler {
    private final ServerContext serverContext;
    private final BasicHotStuffCoordinator coordinator;
    private final Set<RequestKey> pendingRequests = ConcurrentHashMap.newKeySet();
    private final Set<RequestKey> executedRequests = ConcurrentHashMap.newKeySet();
    // Tracks the next expected nonce per sender address to allow sequential multi-tx
    // submission before prior transactions are committed and world state is updated.
    private final Map<Address, Long> pendingNonces = new ConcurrentHashMap<>();

    public MessageHandler(ServerContext serverContext, BasicHotStuffCoordinator coordinator) {
        this.serverContext = serverContext;
        this.serverContext.getPerfectLink().registerReceiver(this::handleIncomingMessage);
        this.coordinator = coordinator;
        this.coordinator.setRequestCommitListener(this::markRequestExecuted);
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
        RequestKey requestKey = new RequestKey(clientId, requestId);

        if (!verifyClientSignature(clientRequest)) {
            System.err.println("[MESSAGE_HANDLER | ERROR] Invalid outer client request signature from " + clientId);
            return;
        }

        if (executedRequests.contains(requestKey)) {
            System.err.println("[MESSAGE_HANDLER | WARN] Replay detected: already executed " + requestKey);
            return;
        }

        if (!pendingRequests.add(requestKey)) {
            System.out.println("[MESSAGE_HANDLER | INFO] Duplicate in-flight request ignored for " + requestKey);
            return;
        }

        switch (clientRequest.getPayloadCase()) {
            case TRANSACTION -> handleTransactionRequest(clientRequest, requestKey);
            case PAYLOAD_NOT_SET -> {
                pendingRequests.remove(requestKey);
                System.err.println("[MESSAGE_HANDLER | ERROR] Empty client payload");
            }
            default -> {
                pendingRequests.remove(requestKey);
                System.err.println("[MESSAGE_HANDLER | ERROR] Unsupported payload type: " + clientRequest.getPayloadCase());
            }
        }
    }

    private void handleTransactionRequest(ClientRequest clientRequest, RequestKey requestKey) {
        String clientId = clientRequest.getClientId();
        var tx = Transaction.fromProto(clientRequest.getTransaction());

        String keyPath = serverContext.getConfig().getTrustedProcessKeyPathString(clientId);
        PublicKey clientPublicKey = KeyLoader.loadPublicKey(keyPath);
        if (clientPublicKey == null) {
            pendingRequests.remove(requestKey);
            System.err.println("[MESSAGE_HANDLER | ERROR] No public key found for " + clientId);
            return;
        }

        Address sender = tx.getFrom();
        long wsNonce = serverContext.getWorldState().accountExists(sender)
                ? serverContext.getWorldState().getNonce(sender)
                : 0;
        long expectedNonce = pendingNonces.getOrDefault(sender, wsNonce);

        var result = TransactionValidator.validate(
                tx,
                clientPublicKey,
                serverContext.getConfig().getSignatureAlgorithm(),
                serverContext.getWorldState(),
                expectedNonce);

        if (!result.valid()) {
            pendingRequests.remove(requestKey);
            System.err.println("[MESSAGE_HANDLER | ERROR] Rejected tx from " + clientId + ": " + result.error());
            return;
        }

        // Advance the pending nonce for this sender.
        pendingNonces.put(sender, expectedNonce + 1);

        System.out.println("[MESSAGE_HANDLER | INFO] Accepted tx request " + requestKey + " from=" + sender + " nonce=" + tx.getNonce());

        coordinator.enqueueClientRequest(clientRequest);
    }

    private void markRequestExecuted(String clientId, int requestId) {
        if (clientId == null || clientId.isEmpty()) {
            return;
        }

        RequestKey requestKey = new RequestKey(clientId, requestId);
        pendingRequests.remove(requestKey);
        executedRequests.add(requestKey);
    }

    private boolean verifyClientSignature(ClientRequest clientRequest) {
        String clientId = clientRequest.getClientId();
        String keyPath = serverContext.getConfig().getTrustedProcessKeyPathString(clientId);
        PublicKey clientPublicKey = KeyLoader.loadPublicKey(keyPath);

        if (clientPublicKey == null) {
            return false;
        }

        ClientRequest unsigned = ClientRequest.newBuilder(clientRequest).clearSignature().build();

        try {
            return Crypto.verifySignature(unsigned.toByteArray(), clientRequest.getSignature().toByteArray(), clientPublicKey, serverContext.getConfig().getSignatureAlgorithm());
        } catch (Exception e) {
            return false;
        }
    }

    private void handleHotStuffMessage(String sourceId, HotStuffMessage hotstuffMsg) {
        coordinator.processMessage(sourceId, hotstuffMsg);
    }

    private static final class RequestKey {
        private final String clientId;
        private final int requestId;

        private RequestKey(String clientId, int requestId) {
            this.clientId = clientId;
            this.requestId = requestId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            RequestKey that = (RequestKey) other;
            return requestId == that.requestId && clientId.equals(that.clientId);
        }

        @Override
        public int hashCode() {
            int result = clientId.hashCode();
            result = 31 * result + requestId;
            return result;
        }
    }
}
