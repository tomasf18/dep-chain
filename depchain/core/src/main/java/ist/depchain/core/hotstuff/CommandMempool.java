package ist.depchain.core.hotstuff;

import ist.depchain.common.ClientRequest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final Comparator<ClientRequest> BY_FEE_DESC =
            Comparator.comparing(CommandMempool::feeOf)
                    .reversed()
                    .thenComparing(ClientRequest::getClientId)
                    .thenComparingInt(ClientRequest::getRequestId);

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

    public synchronized List<ClientRequest> peekBatch(int maxSize) {
        List<ClientRequest> batch = new ArrayList<>(Math.min(maxSize, pending.size()));
        int count = 0;
        for (ClientRequest req : pending) {
            if (count >= maxSize) {
                break;
            }
            batch.add(req);
            count++;
        }
        return batch;
    }

    public synchronized List<ClientRequest> peekFeeBatch(int maxSize, BigInteger minTotalFee) {
        return selectFeeBatch(maxSize, minTotalFee, false);
    }

    public synchronized List<ClientRequest> drainFeeBatch(int maxSize, BigInteger minTotalFee) {
        return selectFeeBatch(maxSize, minTotalFee, true);
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

    private List<ClientRequest> selectFeeBatch(int maxSize, BigInteger minTotalFee, boolean removeSelected) {
        if (maxSize <= 0 || pending.isEmpty()) {
            return List.of();
        }

        List<ClientRequest> ordered = new ArrayList<>(pending);
        ordered.sort(BY_FEE_DESC);

        List<ClientRequest> selected = new ArrayList<>(Math.min(maxSize, ordered.size()));
        BigInteger totalFees = BigInteger.ZERO;

        for (ClientRequest req : ordered) {
            if (selected.size() >= maxSize) {
                break;
            }

            selected.add(req);
            totalFees = totalFees.add(feeOf(req));

            if (totalFees.compareTo(minTotalFee) >= 0) {
                break;
            }
        }

        if (selected.isEmpty() || totalFees.compareTo(minTotalFee) < 0) {
            return List.of();
        }

        if (removeSelected) {
            for (ClientRequest req : selected) {
                pending.remove(req);
                pendingKeys.remove(makeKey(req));
            }
        }

        return selected;
    }

    private static BigInteger feeOf(ClientRequest req) {
        if (!req.hasTransaction()) {
            return BigInteger.ZERO;
        }

        return new java.math.BigInteger(1, req.getTransaction().getGasPrice().toByteArray())
                .multiply(new java.math.BigInteger(1, req.getTransaction().getGasLimit().toByteArray()));
    }
}