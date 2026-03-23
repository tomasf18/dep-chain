package ist.depchain.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.GsonBuilder;

import ist.depchain.core.blockchain.Block;
import ist.depchain.core.blockchain.BlockSerializer;

public class BlockChain {
    private final List<Block> blocks;
    private final String persistenceDir; // null = no persistence

    public BlockChain() {
        this(null);
    }

    public BlockChain(String persistenceDir) {
        this.blocks = new ArrayList<>();
        this.persistenceDir = persistenceDir;
        if (persistenceDir != null) {
            new File(persistenceDir).mkdirs();
        }
    }

    public void addBlock(Block block) {
        blocks.add(block);
        persistBlock(block);
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

    private void persistBlock(Block block) {
        if (persistenceDir == null) return;
        try {
            String json = new GsonBuilder().setPrettyPrinting().create()
                    .toJson(com.google.gson.JsonParser.parseString(BlockSerializer.toJson(block)));
            File file = new File(persistenceDir, "block_" + block.getBlockNumber() + ".json");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json);
            }
        } catch (IOException e) {
            System.err.println("[BLOCKCHAIN | ERROR] Failed to persist block "
                    + block.getBlockNumber() + ": " + e.getMessage());
        }
    }
}
