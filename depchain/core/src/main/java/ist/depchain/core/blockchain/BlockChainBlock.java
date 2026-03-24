package ist.depchain.core.blockchain;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import ist.depchain.common.Transaction;

public class BlockChainBlock {
    private final String blockHash;
    private final String previousBlockHash; // null for genesis
    private final List<Transaction> transactions;
    private final List<TransactionReceipt> receipts; // one per transaction, same order
    private final int blockNumber;
    private final Address proposer; // leader who built this block (null for genesis)

    public BlockChainBlock(String blockHash, String previousBlockHash, List<Transaction> transactions,
                 List<TransactionReceipt> receipts, int blockNumber, Address proposer) {
        this.blockHash = blockHash;
        this.previousBlockHash = previousBlockHash;
        this.transactions = transactions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(transactions));
        this.receipts = receipts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(receipts));
        this.blockNumber = blockNumber;
        this.proposer = proposer;
    }

    /** Convenience constructor without receipts (for genesis or pre-execution). */
    public BlockChainBlock(String blockHash, String previousBlockHash, List<Transaction> transactions, int blockNumber) {
        this(blockHash, previousBlockHash, transactions, null, blockNumber, null);
    }

    /**
     * Compute a deterministic block hash from the block's contents.
     * Hash = Keccak256(previousBlockHash || blockNumber || proposer || tx1Hash || tx2Hash || ...)
     */
    public static String computeBlockHash(String previousBlockHash, int blockNumber,
                                          Address proposer, List<Transaction> transactions) {
        byte[] prevBytes = previousBlockHash != null ? previousBlockHash.getBytes() : new byte[0];
        byte[] blockNumBytes = ByteBuffer.allocate(Integer.BYTES).putInt(blockNumber).array();
        byte[] proposerBytes = proposer != null ? proposer.toArrayUnsafe() : new byte[0];

        int totalSize = 4 + prevBytes.length + 4 + blockNumBytes.length + 4 + proposerBytes.length;
        for (Transaction tx : transactions) {
            totalSize += 4 + 32; // length prefix + 32-byte tx hash
        }

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        putWithLength(buf, prevBytes);
        putWithLength(buf, blockNumBytes);
        putWithLength(buf, proposerBytes);
        for (Transaction tx : transactions) {
            putWithLength(buf, tx.txHash());
        }

        byte[] hash = Hash.sha3(buf.array());
        return Numeric.toHexStringNoPrefix(hash);
    }

    private static void putWithLength(ByteBuffer buf, byte[] data) {
        buf.putInt(data.length);
        buf.put(data);
    }

    // Getters
    public String getBlockHash() { return blockHash; }
    public String getPreviousBlockHash() { return previousBlockHash; }
    public List<Transaction> getTransactions() { return transactions; }
    public List<TransactionReceipt> getReceipts() { return receipts; }
    public int getBlockNumber() { return blockNumber; }
    public Address getProposer() { return proposer; }
}
