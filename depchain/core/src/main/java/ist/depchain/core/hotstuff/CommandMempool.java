package ist.depchain.core.hotstuff;

import ist.depchain.common.ClientRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class CommandMempool {
    // pending commands waiting to be proposed
    private final Queue<ClientRequest> pending = new LinkedList<>();
    // deduplication: track (clientId, requestId) pairs currently in the queue
    private final Set<String> pendingKeys = new HashSet<>();

    private String makeKey(String clientId, int requestId) {
        return clientId + ":" + requestId;
    }

    private String makeKey(ClientRequest req) {
        return makeKey(req.getClientId(), req.getRequestId());
    }

    public synchronized void enqueue(ClientRequest req) {
        String key = makeKey(req);
        if (!pendingKeys.contains(key)) {
            pending.offer(req);
            pendingKeys.add(key);
        }
    }

    public synchronized ClientRequest dequeue() {
        ClientRequest req = pending.poll();
        if (req != null) {
            pendingKeys.remove(makeKey(req));
        }
        return req;
    }

    /**
     * Drain up to {@code maxSize} requests from the front of the queue.
     * Returns an empty list if the mempool is empty.
     */
    public synchronized List<ClientRequest> drainBatch(int maxSize) {
        List<ClientRequest> batch = new ArrayList<>(Math.min(maxSize, pending.size()));
        for (int i = 0; i < maxSize && !pending.isEmpty(); i++) {
            ClientRequest req = pending.poll();
            pendingKeys.remove(makeKey(req));
            batch.add(req);
        }
        return batch;
    }

    public synchronized void discardIfPresent(String clientId, int requestId) {
        String key = makeKey(clientId, requestId);
        if (pendingKeys.contains(key)) {
            pending.removeIf(req -> req.getClientId().equals(clientId) && req.getRequestId() == requestId);
            pendingKeys.remove(key);
        }
    }

    public synchronized void discardConflicting(String clientId) {
        pending.removeIf(req -> {
            if (req.getClientId().equals(clientId)) {
                pendingKeys.remove(makeKey(req));
                return true;
            }
            return false;
        });
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }
}