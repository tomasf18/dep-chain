package ist.depchain.core.hotstuff;

import ist.depchain.common.ClientRequest;

import java.util.Queue;
import java.util.LinkedList;

public class CommandMempool {
    // pending commands waiting to be proposed
    private final Queue<ClientRequest> pending = new LinkedList<>();

    public void enqueue(ClientRequest req) {
        pending.offer(req);
    }

    public ClientRequest dequeue() {
        return pending.poll();
    }

    public void discardIfPresent(String clientId, int requestId) {
        pending.removeIf(req -> req.getClientId().equals(clientId) && req.getRequestId() == requestId);
    }

    public void discardConflicting(String clientId) {
        pending.removeIf(req -> req.getClientId().equals(clientId));
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }
}