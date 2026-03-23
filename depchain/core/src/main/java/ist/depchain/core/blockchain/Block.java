package ist.depchain.core.blockchain;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class Block {
    private final String blockHash;
    private final String previousBlockHash; // null for genesis
    private final List<Transaction> transactions;
    private final int blockNumber;

    public Block(String blockHash, String previousBlockHash, List<Transaction> transactions, int blockNumber) {
        this.blockHash = blockHash;
        this.previousBlockHash = previousBlockHash;
        this.transactions = transactions;
        this.blockNumber = blockNumber;
    }

    /**
     * Returns transactions sorted by fee descending (highest fee first).
     */
    public List<Transaction> getOrderedTransactions() {
        List<Transaction> sorted = new java.util.ArrayList<>(transactions);
        sorted.sort(Comparator.comparing(Transaction::getMaxFee).reversed());
        return Collections.unmodifiableList(sorted);
    }

    // Getters
    public String getBlockHash() { return blockHash; }
    public String getPreviousBlockHash() { return previousBlockHash; }
    public List<Transaction> getTransactions() { return Collections.unmodifiableList(transactions); }
    public int getBlockNumber() { return blockNumber; }
}
