package ist.depchain.client;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.web3j.utils.Numeric;

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
            String status = clientResponse.getStatus();

            // Handle ACCEPTED status - transaction is being processed
            if ("ACCEPTED".equals(status)) {
                CompletableFuture<Void> future = pendingFutures.get(reqId);
                if (future == null || future.isDone()) return;

                System.out.println("[✓] (" + reqId + ", " + sourceId + "): ACCEPTED - transaction is being processed");
                future.complete(null);
                return;
            }

            // Handle REJECTED status
            if ("REJECTED".equals(status)) {
                if (!pendingFutures.containsKey(reqId)) return;

                Set<String> rejectors = rejectionSenders.computeIfAbsent(reqId, k -> ConcurrentHashMap.newKeySet());
                rejectors.add(sourceId);
                System.out.println("[-] (" + reqId + ", " + sourceId + "): REJECTED (" + rejectors.size() + "/" + responsesThreshold + ") error=" + clientResponse.getError());

                if (rejectors.size() >= responsesThreshold) {
                    rejectionSenders.remove(reqId);
                    pendingRequests.remove(reqId);
                    clientContext.getRequestDataMap().remove(reqId);
                    CompletableFuture<Void> future = pendingFutures.remove(reqId);
                    if (future != null) {
                        String reason = clientResponse.getError().isBlank() ? "Transaction rejected by replicas (reqId=" + reqId + ")" : clientResponse.getError();
                        future.completeExceptionally(new RuntimeException(reason));
                    }
                }
                return;
            }

            // Handle COMMITTED status
            if ("COMMITTED".equals(status)) {
                Map<String, Set<String>> differentResponseSenders = pendingRequests.get(reqId);
                if (differentResponseSenders == null) {
                    System.out.println("[ ] (" + reqId + ", " + sourceId + "): COMMITTED but request already processed");
                    return;
                }

                String responseId = makeResponseId(clientResponse);
                Set<String> senders = differentResponseSenders.computeIfAbsent(responseId, key -> ConcurrentHashMap.newKeySet());
                boolean isNewSender = senders.add(sourceId);
                int count = senders.size();

                if (!isNewSender) {
                    System.out.println("[ ] (" + reqId + ", " + sourceId + "): duplicate sender ignored for [" + responseId + "] (" + count + "/" + responsesThreshold + ")");
                    return;
                }

                if (count >= responsesThreshold) {
                    System.out.println("[*] (" + reqId + ", " + sourceId + "): [" + responseId + "] (" + count + "/" + responsesThreshold + ") COMMITTED");

                    printReceiptInfo(clientResponse);

                    String originalData = clientContext.getRequestDataMap().get(reqId);
                    if (originalData != null && !clientContext.getCommitedLog().contains(originalData)) {
                        clientContext.getCommitedLog().add(originalData);
                    }

                    pendingRequests.remove(reqId);
                    rejectionSenders.remove(reqId);
                    clientContext.getRequestDataMap().remove(reqId);

                    // Don't remove from pendingFutures here - it was already completed on ACCEPTED
                } else {
                    System.out.println("[+] (" + reqId + ", " + sourceId + "): [" + responseId + "] (" + count + "/" + responsesThreshold + ")");
                }
                return;
            }

        } catch (Exception e) {
            System.out.println("[ERROR] Error while processing response: " + e.getMessage());
        }
    }

    private String makeResponseId(ClientResponse response) {
        // canonical reply identity based on essential fields only:
        // requestId, committed, blockId - nice for stage 2
        String blockIdStr = response.getBlockId().toStringUtf8();
        String txHashStr = Numeric.toHexStringNoPrefix(response.getTxHash().toByteArray());
        String status = response.getStatus();
        String error = response.getError();
        return response.getRequestId() + ":" + blockIdStr + ":" + response.getCommitted() + ":" + txHashStr + ":" + status + ":" + error;
    }

    private void printReceiptInfo(ClientResponse response) {
        String txHash = response.getTxHash().isEmpty() ? "<empty>" : "0x" + Numeric.toHexStringNoPrefix(response.getTxHash().toByteArray());

        System.out.println("    txHash      = " + txHash);
        System.out.println("    status      = " + response.getStatus());

        if (!response.getError().isBlank()) {
            System.out.println("    error       = " + response.getError());
        }

        if (!response.getGasUsed().isEmpty()) {
            System.out.println("    gasUsed     = " + new java.math.BigInteger(1, response.getGasUsed().toByteArray()));
        }

        if (!response.getFee().isEmpty()) {
            System.out.println("    fee         = " + new java.math.BigInteger(1, response.getFee().toByteArray()));
        }

        if (!response.getContractAddress().isEmpty()) {
            System.out.println("    contractAdr = 0x" + Numeric.toHexStringNoPrefix(response.getContractAddress().toByteArray()));
        }

        if (!response.getReturnData().isEmpty()) {
            System.out.println("    returnData  = 0x" + Numeric.toHexStringNoPrefix(response.getReturnData().toByteArray()));
        }
    }

    public CompletableFuture<Void> registerFuture(int reqId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        pendingFutures.put(reqId, future);
        return future;
    }

    public Map<Integer, Map<String, Set<String>>> getPendingRequests() {
        return pendingRequests;
    }
}