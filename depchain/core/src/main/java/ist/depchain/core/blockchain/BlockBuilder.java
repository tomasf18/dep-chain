package ist.depchain.core.blockchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.utils.Numeric;

import ist.depchain.common.Transaction;

/**
 * Builds a block from a list of transactions.
 *
 * Ordering rule:
 * 1. Highest transaction fee first (gasPrice * gasLimit, descending)
 * 2. Per-sender nonce ordering is strictly preserved (nonce N+1 cannot
 * appear before nonce N for the same sender)
 * 3. Tie-breaker: lexicographic order of transaction hash (ascending)
 *
 */
public class BlockBuilder {

    private static final Comparator<Transaction> FEE_ORDER = Comparator.comparing(Transaction::getMaxFee).reversed()
            .thenComparing(tx -> Numeric.toHexStringNoPrefix(tx.txHash()));

    public BlockBuilder() {
        // No state, so no constructor parameters
    }

    public static BlockChainBlock build(List<Transaction> transactions, BlockChainBlock previousBlock,
            Address proposer) {
        List<Transaction> ordered = orderTransactions(transactions);

        String previousHash = previousBlock != null ? previousBlock.getBlockHash() : null;
        int blockNumber = previousBlock != null ? previousBlock.getBlockNumber() + 1 : 0;

        String blockHash = BlockChainBlock.computeBlockHash(previousHash, blockNumber, proposer, ordered);

        return new BlockChainBlock(blockHash, previousHash, ordered, null, blockNumber, proposer, null);
    }

    /**
     * Orders transactions by descending fee while strictly respecting per-sender
     * nonce order. For each sender, only the transaction with the lowest unseen
     * nonce is eligible; among all eligible transactions the one with the highest
     * fee wins
     */
    public static List<Transaction> orderTransactions(List<Transaction> transactions) {
        Map<Address, LinkedList<Transaction>> transactionsBySender = new HashMap<>();
        for (Transaction tx : transactions) {
            transactionsBySender.computeIfAbsent(tx.getFrom(), ignored -> new LinkedList<>()).add(tx);
        }

        // for each sender, sort their transactions by nonce ascending, so we can easily
        // pick the next eligible transaction
        for (LinkedList<Transaction> senderTransactions : transactionsBySender.values()) {
            senderTransactions.sort(Comparator.comparingLong(Transaction::getNonce));
        }

        // use a priority queue to always pick the eligible transaction with the highest
        // fee
        PriorityQueue<Transaction> readyTransactions = new PriorityQueue<>(FEE_ORDER);
        for (LinkedList<Transaction> senderTransactions : transactionsBySender.values()) {
            if (!senderTransactions.isEmpty()) {
                readyTransactions.add(senderTransactions.pollFirst());
            }
        }

        // repeatedly pick the next transaction with the highest fee, and add the next
        // transaction from the same sender to the queue if there is one
        List<Transaction> orderedTransactions = new ArrayList<>(transactions.size());
        while (!readyTransactions.isEmpty()) {
            Transaction nextTransaction = readyTransactions.poll();
            orderedTransactions.add(nextTransaction);

            LinkedList<Transaction> remainingTransactions = transactionsBySender.get(nextTransaction.getFrom());
            if (remainingTransactions != null && !remainingTransactions.isEmpty()) {
                readyTransactions.add(remainingTransactions.pollFirst());
            }
        }
        return orderedTransactions;
    }

    public static BlockChainBlock finalize(BlockChainBlock executedBlock, List<TransactionReceipt> receipts,
            String stateHash) {
        return new BlockChainBlock(
                executedBlock.getBlockHash(),
                executedBlock.getPreviousBlockHash(),
                executedBlock.getTransactions(),
                receipts,
                executedBlock.getBlockNumber(),
                executedBlock.getProposer(),
                stateHash);
    }
}