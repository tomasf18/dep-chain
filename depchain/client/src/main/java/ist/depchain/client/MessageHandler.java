package ist.depchain.client;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import ist.depchain.common.ClientResponse;

public class MessageHandler {
    private final ClientContext clientContext;
    
    // requestId -> (response digest -> set of distinct replica sender ids)
    private final Map<Integer, Map<String, Set<String>>> pendingRequests = new ConcurrentHashMap<>();
    private final int responsesThreshold;

    // Per-request futures: complete normally on commit, exceptionally on rejection
    private final Map<Integer, CompletableFuture<Void>> pendingFutures = new ConcurrentHashMap<>();
    // requestId -> set of replica IDs that sent a rejection response
    private final Map<Integer, Set<String>> rejectionSenders = new ConcurrentHashMap<>();

    public MessageHandler(ClientContext clientContext) {
        this.clientContext = clientContext;
        this.clientContext.getAuthenticatedPerfectLink().registerReceiver(this::handleIncomingResponse);
        this.responsesThreshold = clientContext.getConfig().getThreshold();
    }

    private void handleIncomingResponse(String sourceId, byte[] data) {
        try {
            ClientResponse clientResponse = ClientResponse.parseFrom(data);
            int reqId = clientResponse.getRequestId();

            if (!clientResponse.getCommitted()) {
                // Rejection response from a replica
                if (!pendingFutures.containsKey(reqId)) return;
                Set<String> rejectors = rejectionSenders.computeIfAbsent(reqId, k -> ConcurrentHashMap.newKeySet());
                rejectors.add(sourceId);
                System.out.println("[-] (" + reqId + ", " + sourceId + "): REJECTED (" + rejectors.size() + "/" + responsesThreshold + ")");
                if (rejectors.size() >= responsesThreshold) {
                    rejectionSenders.remove(reqId);
                    pendingRequests.remove(reqId);
                    clientContext.getRequestDataMap().remove(reqId);
                    CompletableFuture<Void> future = pendingFutures.remove(reqId);
                    if (future != null) {
                        future.completeExceptionally(new RuntimeException("Transaction rejected by replicas (reqId=" + reqId + ")"));
                    }
                }
                return;
            }

            Map<String, Set<String>> differentResponseSenders = pendingRequests.get(reqId);
            if (differentResponseSenders == null) {
                System.out.println("[ ] (" + reqId + ", " + sourceId + "): nothing to do...");
                return;
            }

            String responseId = makeResponseId(clientResponse);
            Set<String> senders = differentResponseSenders.computeIfAbsent(responseId, key -> ConcurrentHashMap.newKeySet());
            boolean isNewSender = senders.add(sourceId);
            int count = senders.size();

            if (!isNewSender) {
                System.out.println("[ ] (" + reqId + ", " + sourceId + "): duplicate sender ignored for ["
                        + responseId + "] (" + count + "/" + responsesThreshold + ")");
                return;
            }

            if (count >= responsesThreshold) {
                System.out.println("[*] (" + reqId + ", " + sourceId + "): [" + responseId + "] (" + count + "/" + responsesThreshold + ") COMMITTED");
                String originalData = clientContext.getRequestDataMap().get(reqId);
                if (originalData != null && !clientContext.getCommitedLog().contains(originalData)) {
                    clientContext.getCommitedLog().add(originalData);
                }
                pendingRequests.remove(reqId);
                clientContext.getRequestDataMap().remove(reqId);
                CompletableFuture<Void> future = pendingFutures.remove(reqId);
                if (future != null) future.complete(null);
            } else {
                System.out.println("[+] (" + reqId + ", " + sourceId + "): [" + responseId + "] (" + count + "/" + responsesThreshold + ")");
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Error while processing response: " + e.getMessage());
        }
    }

    private String makeResponseId(ClientResponse response) {
        // canonical reply identity based on essential fields only:
        // requestId, committed, blockId - nice for stage 2
        String blockIdStr = response.getBlockId().toStringUtf8();
        return response.getRequestId() + ":" + blockIdStr + ":" + response.getCommitted();
    }    

    /**
     * Registers a {@link CompletableFuture} for the given request ID.
     * Completed normally on commit, exceptionally on rejection.
     */
    public CompletableFuture<Void> registerFuture(int reqId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        pendingFutures.put(reqId, future);
        return future;
    }

    public Map<Integer, Map<String, Set<String>>> getPendingRequests() {
        return pendingRequests;
    }
}
