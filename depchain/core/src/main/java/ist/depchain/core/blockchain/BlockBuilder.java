package ist.depchain.core.blockchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.utils.Numeric;

import ist.depchain.common.Transaction;

/**
 * Builds a block from a list of transactions.
 *
 * Ordering rule (from spec):
 *   1. Highest transaction fee first (gasPrice * gasLimit, descending)
 *   2. Tie-breaker: lexicographic order of transaction hash (ascending)
 *
 * The tie-breaker ensures deterministic ordering across all replicas
 * even when multiple transactions have identical fees.
 */
public class BlockBuilder {

    private static final Comparator<Transaction> TX_ORDER =
            Comparator.comparing(Transaction::getMaxFee).reversed()
                    .thenComparing(tx -> Numeric.toHexStringNoPrefix(tx.txHash()));

    public static BlockChainBlock build(List<Transaction> transactions, BlockChainBlock previousBlock, Address proposer) {
        // 1. Sort by fee descending, then tx hash ascending for tie-breaking
        List<Transaction> ordered = new ArrayList<>(transactions);
        ordered.sort(TX_ORDER);

        // 2. Compute block metadata
        String previousHash = previousBlock != null ? previousBlock.getBlockHash() : null;
        int blockNumber = previousBlock != null ? previousBlock.getBlockNumber() + 1 : 0;

        // 3. Compute deterministic block hash
        String blockHash = BlockChainBlock.computeBlockHash(previousHash, blockNumber, proposer, ordered);

        // 4. Build block (receipts are empty - filled after execution)
        return new BlockChainBlock(blockHash, previousHash, ordered, null, blockNumber, proposer);
    }

    /**
     * Create a finalized block with execution receipts.
     * Called after TransactionExecutor has processed all transactions.
     */
    public static BlockChainBlock finalize(BlockChainBlock executedBlock, List<TransactionReceipt> receipts) {
        return new BlockChainBlock(
                executedBlock.getBlockHash(),
                executedBlock.getPreviousBlockHash(),
                executedBlock.getTransactions(),
                receipts,
                executedBlock.getBlockNumber(),
                executedBlock.getProposer()
        );
    }
}
