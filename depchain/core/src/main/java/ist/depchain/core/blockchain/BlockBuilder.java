package ist.depchain.core.blockchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.utils.Numeric;

import ist.depchain.common.Transaction;

/**
 * Builds a block from a list of transactions.
 *
 * Ordering rule:
 *   1. Highest transaction fee first (gasPrice * gasLimit, descending)
 *   2. Per-sender nonce ordering is strictly preserved (nonce N+1 cannot
 *      appear before nonce N for the same sender)
 *   3. Tie-breaker: lexicographic order of transaction hash (ascending)
 *
 * Implementation: group transactions by sender sorted by nonce, then use a
 * priority queue that always exposes only the next eligible (lowest-nonce)
 * transaction per sender.
 */
public class BlockBuilder {

    private static final Comparator<Transaction> FEE_ORDER =
            Comparator.comparing(Transaction::getMaxFee).reversed()
                    .thenComparing(tx -> Numeric.toHexStringNoPrefix(tx.txHash()));

    public static BlockChainBlock build(List<Transaction> transactions, BlockChainBlock previousBlock, Address proposer) {
        List<Transaction> ordered = orderTransactions(transactions);

        String previousHash = previousBlock != null ? previousBlock.getBlockHash() : null;
        int blockNumber = previousBlock != null ? previousBlock.getBlockNumber() + 1 : 0;

        String blockHash = BlockChainBlock.computeBlockHash(previousHash, blockNumber, proposer, ordered);

        return new BlockChainBlock(blockHash, previousHash, ordered, null, blockNumber, proposer, null);
    }

    /**
     * Orders transactions by descending fee while strictly respecting per-sender
     * nonce order.  For each sender, only the transaction with the lowest unseen
     * nonce is eligible; among all eligible transactions the one with the highest
     * fee wins.
     */
    public static List<Transaction> orderTransactions(List<Transaction> transactions) {
        // Group by sender, sorted by nonce ascending within each group
        Map<Address, Queue<Transaction>> bySender = new HashMap<>();
        for (Transaction tx : transactions) {
            bySender.computeIfAbsent(tx.getFrom(), k -> new LinkedList<>()).add(tx);
        }
        for (Queue<Transaction> q : bySender.values()) {
            ((LinkedList<Transaction>) q).sort(Comparator.comparingLong(Transaction::getNonce));
        }

        // Priority queue of the head (lowest-nonce) tx from each sender
        PriorityQueue<Transaction> pq = new PriorityQueue<>(FEE_ORDER);
        for (Queue<Transaction> q : bySender.values()) {
            if (!q.isEmpty()) {
                pq.add(q.poll());
            }
        }

        List<Transaction> ordered = new ArrayList<>(transactions.size());
        while (!pq.isEmpty()) {
            Transaction best = pq.poll();
            ordered.add(best);
            Queue<Transaction> remaining = bySender.get(best.getFrom());
            if (remaining != null && !remaining.isEmpty()) {
                pq.add(remaining.poll());
            }
        }
        return ordered;
    }

    public static BlockChainBlock finalize(BlockChainBlock executedBlock, List<TransactionReceipt> receipts, String stateHash) {
        return new BlockChainBlock(
                executedBlock.getBlockHash(),
                executedBlock.getPreviousBlockHash(),
                executedBlock.getTransactions(),
                receipts,
                executedBlock.getBlockNumber(),
                executedBlock.getProposer(),
                stateHash
        );
    }
}