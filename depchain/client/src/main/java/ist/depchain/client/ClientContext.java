package ist.depchain.client;

import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.PerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
/* protobuf classes */
import ist.depchain.common.ClientResponse;
import ist.depchain.common.utils.Config;
import ist.depchain.network.crypto.Authenticator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.HashMap;
import com.google.protobuf.ByteString;

public class ClientContext {
    private final Config config;
    
    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink authenticatedPerfectLink;

    private final AtomicInteger requestId = new AtomicInteger(0);
    // requestId -> (blockId -> count of matching committed responses)
    private Map<Integer, Map<String, Integer>> pendingRequests = new HashMap<>();
    private int responsesThreshold; // f+1 matching responses required

    private final Map<Integer, String> requestDataMap = new ConcurrentHashMap<>();
    private final List<String> commitedLog = Collections.synchronizedList(new ArrayList<>());

    public ClientContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        authenticatedPerfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config, fairLossLink));
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

            String blockKey = clientResponse.getBlockId().toStringUtf8();
            Map<String, Integer> blockCounts = pendingRequests.get(reqId);
            int count = blockCounts.merge(blockKey, 1, Integer::sum);

            if (count >= responsesThreshold) {
                System.out.println("[*] (" +  reqId + ", " + sourceId + "): [" + blockKey +"] (" + count + "/" + responsesThreshold + ") COMMITED");
                pendingRequests.remove(reqId);
            } else {
                System.out.println("[+] (" +  reqId + ", " + sourceId + "): [" + blockKey +"] (" + count + "/" + responsesThreshold + ")");
            }
        } catch(Exception e) {
            System.out.println("[ERROR] Error while processing request: " + e.getMessage());
        }
    }

    public void waitForHandshakes() throws InterruptedException {
        authenticatedPerfectLink.getAuthenticator().waitForHandshakesComplete();
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

    public List<String> getCommitedLog() {
        return commitedLog;
    }
}
