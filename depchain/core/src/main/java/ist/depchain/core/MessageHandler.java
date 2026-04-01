package ist.depchain.core;

import ist.depchain.common.Transaction;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.ClientResponse;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.common.ApplicationMessage;
import ist.depchain.network.crypto.KeyLoader;
import ist.depchain.core.blockchain.TransactionValidator;
import ist.depchain.core.blockchain.ValidationResult;

import java.security.PublicKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.hyperledger.besu.datatypes.Address;

public class MessageHandler {
    private final ServerContext serverContext;
    private final BasicHotStuffCoordinator coordinator;
    private final Set<RequestKey> pendingRequests = ConcurrentHashMap.newKeySet();
    // We keep track of executed requests to prevent replay attacks, since clients might resend requests if they don't receive a response (e.g. due to network issues)
    // This is separate from pendingRequests because a request can only be removed from pendingRequests when we first accept it, but we want to mark it as executed only 
    // after it's actually committed in the blockchain.
    private final Set<RequestKey> executedRequests = ConcurrentHashMap.newKeySet();
    // Track the highest nonce seen per sender address to support pipelined transactions
    private final Map<Address, Set<Long>> pendingNonces = new ConcurrentHashMap<>();

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
                    handleClientRequest(wrapper.getClientRequest());
                    break;
                case HOTSTUFF_MESSAGE:
                    handleHotStuffMessage(sourceId, wrapper.getHotstuffMessage());
                    break;
                default:
                    System.err.println("[MESSAGE_HANDLER | ERROR] - Unknown message type from " + sourceId);
            }
        } catch (Exception e) {
            System.err.println("[MESSAGE_HANDLER | ERROR] - Failed to handle message from " + sourceId + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private void handleClientRequest(ClientRequest clientRequest) {
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
            case TRANSACTION:
                 handleTransactionRequest(clientRequest, requestKey);
                 break;
            case PAYLOAD_NOT_SET:
                pendingRequests.remove(requestKey);
                System.err.println("[MESSAGE_HANDLER | ERROR] Empty client payload");
                break;
            default: 
                pendingRequests.remove(requestKey);
                System.err.println("[MESSAGE_HANDLER | ERROR] Unsupported payload type: " + clientRequest.getPayloadCase());
        }
    }

    private void handleTransactionRequest(ClientRequest clientRequest, RequestKey requestKey) {
        String clientId = clientRequest.getClientId();
        Transaction tx = Transaction.fromProto(clientRequest.getTransaction());

        String keyPath = serverContext.getConfig().getTrustedProcessKeyPathString(clientId);
        PublicKey clientPublicKey = KeyLoader.loadPublicKey(keyPath);
        if (clientPublicKey == null) {
            pendingRequests.remove(requestKey);
            System.err.println("[MESSAGE_HANDLER | ERROR] No public key found for " + clientId);
            return;
        }

        Set<Long> pendingNoncesForSender = pendingNonces.computeIfAbsent(tx.getFrom(), k -> ConcurrentHashMap.newKeySet());
        
        // Validate transaction structure before admitting it to consensus.
        ValidationResult result = TransactionValidator.validate(tx, clientPublicKey, serverContext.getConfig().getSignatureAlgorithm(), serverContext.getWorldState(), pendingNoncesForSender);

        if (!result.isValid()) {
            pendingRequests.remove(requestKey);
            System.err.println("[MESSAGE_HANDLER | ERROR] Transaction validation failed for " + requestKey + ": " + result.getErrorMessage());
            sendRejectionResponse(clientId, clientRequest.getRequestId(), result.getErrorMessage());
            return;
        }

        // Transaction is valid - track this nonce as pending and accept it
        if (tx.getNonce() == serverContext.getWorldState().getNonce(tx.getFrom()) + 1) {
            // If this transaction has the next expected nonce, we can clean up any pending nonces that are now stale (already committed)
            pendingNoncesForSender.removeIf(nonce -> nonce <= tx.getNonce());
        }
        pendingNoncesForSender.add(tx.getNonce());
        System.out.println("[MESSAGE_HANDLER | INFO] Accepted tx request " + requestKey + " from=" + tx.getFrom() + " nonce=" + tx.getNonce());

        coordinator.enqueueClientRequest(clientRequest);
        // Send immediate ACCEPTED response to client
        sendAcceptedResponse(clientId, clientRequest.getRequestId());
    }

    private void handleHotStuffMessage(String sourceId, HotStuffMessage hotstuffMsg) {
        coordinator.processMessage(sourceId, hotstuffMsg);
    }

    private void sendRejectionResponse(String clientId, int reqId, String reason) {
        try {
            String errorMessage = (reason == null || reason.isBlank()) ? "tx validation failed" : reason;

            ClientResponse rejection = ClientResponse.newBuilder()
                    .setClientId(clientId)
                    .setRequestId(reqId)
                    .setCommitted(false)
                    .setStatus("REJECTED")
                    .setError(errorMessage)
                    .build();
            serverContext.getPerfectLink().send(clientId, rejection.toByteArray());
        } catch (Exception e) {
            System.err.println("[MESSAGE_HANDLER | ERROR] Failed to send rejection to " + clientId + ": " + e.getMessage());
        }
    }

    private void sendAcceptedResponse(String clientId, int reqId) {
        try {
            ClientResponse accepted = ClientResponse.newBuilder()
                    .setClientId(clientId)
                    .setRequestId(reqId)
                    .setCommitted(false)
                    .setStatus("ACCEPTED")
                    .build();
            serverContext.getPerfectLink().send(clientId, accepted.toByteArray());
        } catch (Exception e) {
            System.err.println("[MESSAGE_HANDLER | ERROR] Failed to send ACCEPTED response to " + clientId + ": " + e.getMessage());
        }
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

        @Override
        public String toString() {
            return "RequestKey{" +
                    "clientId='" + clientId + '\'' +
                    ", requestId=" + requestId +
                    '}';
        }
    }
}
