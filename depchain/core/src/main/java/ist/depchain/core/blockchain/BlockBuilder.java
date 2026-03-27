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
 * Ordering rule:
 *   1. Highest transaction fee first (gasPrice * gasLimit, descending)
 *   2. Tie-breaker: lexicographic order of transaction hash (ascending)
 */
public class BlockBuilder {

    private static final Comparator<Transaction> TX_ORDER =
            Comparator.comparing(Transaction::getMaxFee).reversed()
                    .thenComparing(tx -> Numeric.toHexStringNoPrefix(tx.txHash()));

    public static BlockChainBlock build(List<Transaction> transactions, BlockChainBlock previousBlock, Address proposer) {
        List<Transaction> ordered = new ArrayList<>(transactions);
        ordered.sort(TX_ORDER);

        String previousHash = previousBlock != null ? previousBlock.getBlockHash() : null;
        int blockNumber = previousBlock != null ? previousBlock.getBlockNumber() + 1 : 0;

        String blockHash = BlockChainBlock.computeBlockHash(previousHash, blockNumber, proposer, ordered);

        return new BlockChainBlock(blockHash, previousHash, ordered, null, blockNumber, proposer, null);
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