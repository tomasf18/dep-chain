package ist.depchain.client;

import ist.depchain.network.abstractions.PerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
/* protobuf classes */
import ist.depchain.common.ClientResponse;
import ist.depchain.common.utils.Config;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.HashMap;

public class ClientContext {
    private final Config config;
    
    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final PerfectLink perfectLink;

    private final AtomicInteger requestId = new AtomicInteger(0);
    private Map<Integer, Integer> pendingRequests = new HashMap<>(); // requestId -> ack count
    private int responsesThreshold; // number of acks required to consider a request committed

    public ClientContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        perfectLink = new PerfectLink(config, stubbornLink, fairLossLink, null);
        this.responsesThreshold = config.getF() + 1; 
    }
    
    public void start() {
        perfectLink.registerReceiver(this::handleIncomingResponse);
        perfectLink.start();
    }

    private void handleIncomingResponse(String sourceId, byte[] data) {
        try {
            ClientResponse clientResponse = ClientResponse.parseFrom(data);

            System.out.println("[RECEIVED] Response from: " + sourceId + " for request Id: " + clientResponse.getRequestId() + " | Committed: " + clientResponse.getCommitted());

            int reqId = clientResponse.getRequestId();
            if (pendingRequests.containsKey(reqId) && clientResponse.getCommitted()) {
                int ackCount = pendingRequests.get(reqId) + 1;
                pendingRequests.put(reqId, ackCount);
                if (ackCount >= responsesThreshold) {
                    System.out.println("[COMMITTED] Request " + reqId + " is considered committed with " + ackCount + " acks | Block ID: " + clientResponse.getBlockId().toStringUtf8());
                    pendingRequests.remove(reqId); // clean up
                } else {
                    System.out.println("[PENDING] Request " + reqId + " has " + ackCount + "/" + responsesThreshold + " acks, waiting for more...");
                }
            }
        } catch(Exception e) {
            System.out.println("[ERROR] Error while processing request: " + e.getMessage());
        }
    }

    public void stop() {
        perfectLink.stop();
    }

    /* Getters */
    
    public Config getConfig() {
        return config;
    }

    public PerfectLink getPerfectLink() {
        return perfectLink;
    }

    public AtomicInteger getRequestId() {
        return requestId;
    }

    public Map<Integer, Integer> getPendingRequests() {
        return pendingRequests; 
    }
}
