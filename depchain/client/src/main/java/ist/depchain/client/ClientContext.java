package ist.depchain.client;

import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;

/* protobuf classes */
import ist.depchain.common.ClientResponse;
import ist.depchain.common.utils.Config;
import ist.depchain.network.crypto.Authenticator;
import ist.depchain.network.crypto.KeyLoader;

import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.HashMap;

public class ClientContext {
    private final Config config;
    
    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink authenticatedPerfectLink;
    private final PrivateKey privateKey;

    private final AtomicInteger requestId = new AtomicInteger(0);
    // requestId -> (response digest -> count of matching committed responses)
    private final Map<Integer, Map<String, Integer>> pendingRequests = new HashMap<>();
    private final int responsesThreshold; // f+1 matching responses required

    private final Map<Integer, String> requestDataMap = new ConcurrentHashMap<>();
    private final List<String> commitedLog = Collections.synchronizedList(new ArrayList<>());

    public ClientContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        authenticatedPerfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));
        this.privateKey = KeyLoader.loadPrivateKey(config.getSelfPrivateKeyPathString());
        if (this.privateKey == null) {
            throw new RuntimeException("Failed to load private key from " + config.getSelfPrivateKeyPathString());
        }
        this.responsesThreshold = config.getF() + 1;
    }
    
    public void start() {
        authenticatedPerfectLink.registerReceiver(this::handleIncomingResponse);
        authenticatedPerfectLink.start();
    }

    private void handleIncomingResponse(String sourceId, byte[] data) {
        try {
            ClientResponse clientResponse = ClientResponse.parseFrom(data);

            int reqId = clientResponse.getRequestId();
            if (!pendingRequests.containsKey(reqId) || !clientResponse.getCommitted()) {
                System.out.println("[ ] (" +  reqId + ", " + sourceId + "): nothing to do...");
                return;
            }

            String responseDigest = digestMessage(clientResponse.toByteArray());
            Map<String, Integer> digestCounts = pendingRequests.get(reqId);
            int count = digestCounts.merge(responseDigest, 1, Integer::sum);

            if (count >= responsesThreshold) {
                System.out.println("[*] (" +  reqId + ", " + sourceId + "): [" + responseDigest +"] (" + count + "/" + responsesThreshold + ") COMMITED");
                String originalData = requestDataMap.get(reqId);
                if(originalData != null && !commitedLog.contains(originalData)) {
                    commitedLog.add(originalData);
                }
                pendingRequests.remove(reqId);
                requestDataMap.remove(reqId);
            } else {
                System.out.println("[+] (" +  reqId + ", " + sourceId + "): [" + responseDigest +"] (" + count + "/" + responsesThreshold + ")");
            }
        } catch(Exception e) {
            System.out.println("[ERROR] Error while processing request: " + e.getMessage());
        }
    }

    private String digestMessage(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
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

    public Map<Integer, Map<String, Integer>> getPendingRequests() {
        return pendingRequests;
    }

    public void registerRequestInMap(int requestId, String requestData) {
        requestDataMap.put(requestId, requestData);
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public List<String> getCommitedLog() {
        return commitedLog;
    }
}
