package ist.depchain.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.BlockSerializer;

public class BlockChain {
    private static final String PERSISTENCE_BASE_DIR = "data/blocks/";
    private final List<BlockChainBlock> blocks;
    private final String persistenceDir; // null = no persistence

    public BlockChain() {
        this(null);
    }

    public BlockChain(String replicaId) {
        this.blocks = new ArrayList<>();
        if (replicaId != null) {
            // if it's an absolute path, use it directly; otherwise, treat as replicaId and prepend baseDir
            File file = new File(replicaId);
            this.persistenceDir = file.isAbsolute() ? replicaId : PERSISTENCE_BASE_DIR + replicaId;
        } else {
            this.persistenceDir = null;
        }
        if (persistenceDir != null) {
            new File(persistenceDir).mkdirs();
        }
    }

    public synchronized void addBlock(BlockChainBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("block cannot be null");
        }

        if (blocks.isEmpty()) {
            // genesis block validation
            if (block.getBlockNumber() != 0) {
                throw new IllegalStateException("Genesis block must have number 0");
            }
            if (block.getPreviousBlockHash() != null) {
                throw new IllegalStateException("Genesis block must have previousBlockHash = null");
            }
        } else {
            BlockChainBlock latest = getLatestBlock();

            if (!latest.getBlockHash().equals(block.getPreviousBlockHash())) {
                throw new IllegalStateException("Invalid block linkage: expected previous hash " + latest.getBlockHash() + " but got " + block.getPreviousBlockHash());
            }

            if (block.getBlockNumber() != latest.getBlockNumber() + 1) {
                throw new IllegalStateException("Invalid block number: expected " + (latest.getBlockNumber() + 1) + " but got " + block.getBlockNumber());
            }
        }

        blocks.add(block);
        persistBlock(block);
    }

    public BlockChainBlock getBlock(int index) {
        if (index < 0 || index >= blocks.size()) return null;
        return blocks.get(index);
    }

    public BlockChainBlock getLatestBlock() {
        if (blocks.isEmpty()) return null;
        return blocks.get(blocks.size() - 1);
    }

    public int getHeight() {
        return blocks.size();
    }

    public List<BlockChainBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }
    
    private void persistBlock(BlockChainBlock block) {
        if (persistenceDir == null) return;
        try {
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(BlockSerializer.toJson(block)));
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
