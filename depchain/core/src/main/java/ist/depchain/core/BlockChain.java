package ist.depchain.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ist.depchain.core.blockchain.Block;

public class BlockChain {
    private final List<Block> blocks;

    public BlockChain() {
        this.blocks = new ArrayList<>();
    }

    public void addBlock(Block block) {
        blocks.add(block);
    }

    public Block getBlock(int index) {
        if (index < 0 || index >= blocks.size()) return null;
        return blocks.get(index);
    }

    public Block getLatestBlock() {
        if (blocks.isEmpty()) return null;
        return blocks.get(blocks.size() - 1);
    }

    public int getHeight() {
        return blocks.size();
    }

    public List<Block> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    /**
     * Legacy append for backward compatibility with Stage 1 consensus only.
     * DO NOT USE this for Stage 2.
     */ 
    public void append(String data) {
        String prevHash = blocks.isEmpty() ? null : getLatestBlock().getBlockHash();
        Block block = new Block(
            String.valueOf(data.hashCode()),
            prevHash,
            Collections.emptyList(),
            blocks.size()
        );
        blocks.add(block);
    }

    public void showLog() {
        System.out.println("=== BlockChain Log ===");
        System.out.println("Total blocks: " + blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (i > 0) System.out.print(" <- ");
            System.out.print(i + ": [hash=" + b.getBlockHash()
                    + ", txs=" + b.getTransactions().size() + "]");
        }
        System.out.println("\n=====================");
    }
}
