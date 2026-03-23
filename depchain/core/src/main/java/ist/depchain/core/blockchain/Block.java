package ist.depchain.core.blockchain;

import java.util.ArrayList;
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
        this.transactions = transactions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(transactions));
        this.blockNumber = blockNumber;
    }

    // Getters
    public String getBlockHash() { return blockHash; }
    public String getPreviousBlockHash() { return previousBlockHash; }
    /**
     * Returns transactions in the exact order stored in the block.
     * Ordering policy is decided by the block builder / leader, not by Block itself.
     */
    public List<Transaction> getTransactions() { return Collections.unmodifiableList(transactions); }
    public int getBlockNumber() { return blockNumber; }
}
