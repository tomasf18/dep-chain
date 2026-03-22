package ist.depchain.core.hotstuff;

import ist.depchain.common.ClientRequest;

import java.util.Queue;
import java.util.LinkedList;
import java.util.Set;
import java.util.HashSet;

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