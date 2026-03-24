package ist.depchain.client;

import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;

/* protobuf classes */
import ist.depchain.common.ClientResponse;
import ist.depchain.common.utils.Config;
import ist.depchain.network.crypto.Authenticator;
import ist.depchain.network.crypto.KeyLoader;
import ist.depchain.common.utils.AddressUtils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.hyperledger.besu.datatypes.Address;

public class ClientContext {
    private final Config config;
    
    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink authenticatedPerfectLink;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final Address selfAddress;

    private final AtomicInteger requestId = new AtomicInteger(0);
    // requestId -> (response digest -> set of distinct replica sender ids)
    private final Map<Integer, Map<String, Set<String>>> pendingRequests = new ConcurrentHashMap<>();
    private final int responsesThreshold;

    private final Map<Integer, String> requestDataMap = new ConcurrentHashMap<>();
    private final List<String> commitedLog = Collections.synchronizedList(new ArrayList<>());

    // Nonce managed client-side; only incremented after a successful commit
    private final AtomicLong nonce = new AtomicLong(0);
    // Per-request futures: complete normally on commit, exceptionally on rejection
    private final Map<Integer, CompletableFuture<Void>> pendingFutures = new ConcurrentHashMap<>();
    // requestId -> set of replica IDs that sent a rejection response
    private final Map<Integer, Set<String>> rejectionSenders = new ConcurrentHashMap<>();

    public ClientContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        authenticatedPerfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));
        this.privateKey = KeyLoader.loadPrivateKey(config.getSelfPrivateKeyPathString());
        if (this.privateKey == null) {
            throw new RuntimeException("Failed to load private key from " + config.getSelfPrivateKeyPathString());
        }
        this.publicKey = KeyLoader.loadPublicKey(config.getSelfPublicKeyPathString());
        if (this.publicKey == null) {
            throw new RuntimeException("Failed to load public key for " + config.getSelfId());
        }
        this.selfAddress = AddressUtils.deriveAddress(publicKey);
        this.responsesThreshold = config.getThreshold();
    }
    
    public void start() {
        authenticatedPerfectLink.registerReceiver(this::handleIncomingResponse);
        authenticatedPerfectLink.start();
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
                System.out.println("[-] (" + reqId + ", " + sourceId + "): REJECTED ("
                        + rejectors.size() + "/" + responsesThreshold + ")");
                if (rejectors.size() >= responsesThreshold) {
                    rejectionSenders.remove(reqId);
                    pendingRequests.remove(reqId);
                    requestDataMap.remove(reqId);
                    CompletableFuture<Void> future = pendingFutures.remove(reqId);
                    if (future != null) {
                        future.completeExceptionally(
                                new RuntimeException("Transaction rejected by replicas (reqId=" + reqId + ")"));
                    }
                }
                return;
            }

            Map<String, Set<String>> identitySenders = pendingRequests.get(reqId);
            if (identitySenders == null) {
                System.out.println("[ ] (" + reqId + ", " + sourceId + "): nothing to do...");
                return;
            }

            String replyIdentity = makeReplyIdentity(clientResponse);
            Set<String> senders = identitySenders.computeIfAbsent(replyIdentity, key -> ConcurrentHashMap.newKeySet());
            boolean isNewSender = senders.add(sourceId);
            int count = senders.size();

            if (!isNewSender) {
                System.out.println("[ ] (" + reqId + ", " + sourceId + "): duplicate sender ignored for ["
                        + replyIdentity + "] (" + count + "/" + responsesThreshold + ")");
                return;
            }

            if (count >= responsesThreshold) {
                System.out.println("[*] (" + reqId + ", " + sourceId + "): [" + replyIdentity + "] ("
                        + count + "/" + responsesThreshold + ") COMMITTED");
                String originalData = requestDataMap.get(reqId);
                if (originalData != null && !commitedLog.contains(originalData)) {
                    commitedLog.add(originalData);
                }
                pendingRequests.remove(reqId);
                requestDataMap.remove(reqId);
                CompletableFuture<Void> future = pendingFutures.remove(reqId);
                if (future != null) future.complete(null);
            } else {
                System.out.println("[+] (" + reqId + ", " + sourceId + "): [" + replyIdentity + "] ("
                        + count + "/" + responsesThreshold + ")");
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Error while processing response: " + e.getMessage());
        }
    }

    private String makeReplyIdentity(ClientResponse response) {
        // canonical reply identity based on essential fields only:
        // requestId, committed, blockId - nice for stage 2
        String blockIdStr = response.getBlockId().toStringUtf8();
        return response.getRequestId() + ":" + blockIdStr + ":" + response.getCommitted();
    }

    /** Returns the current nonce without advancing it. */
    public long peekNonce() {
        return nonce.get();
    }

    /** Advances the nonce by one. Called only after a confirmed commit. */
    public void incrementNonce() {
        nonce.incrementAndGet();
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

    public void stop() {
        authenticatedPerfectLink.stop();
    }

    /* Getters */
    
    public Config getConfig() {
        return config;
    }

    public AuthenticatedPerfectLink getAuthenticatedPerfectLink() {
        return authenticatedPerfectLink;
    }

    public AtomicInteger getRequestId() {
        return requestId;
    }

    public Map<Integer, Map<String, Set<String>>> getPendingRequests() {
        return pendingRequests;
    }

    public void registerRequestInMap(int requestId, String requestData) {
        requestDataMap.put(requestId, requestData);
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public Address getSelfAddress() {
        return selfAddress;
    }

    public List<String> getCommitedLog() {
        return commitedLog;
    }
}
