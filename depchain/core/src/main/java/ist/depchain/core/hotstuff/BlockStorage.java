package ist.depchain.core.hotstuff;
// package ist.depchain.core;

import ist.depchain.common.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockStorage {
    private final Map<Integer, Block> blocks = new ConcurrentHashMap<>();

    public BlockStorage() {
        Block genesisBlock = Block.newBuilder()
                .setId(0)
                .setParentId(-1)
                .build();
        this.putBlock(genesisBlock);
    }

    public void putBlock(Block block) {
         blocks.put(block.getId(), block);
    }
    public Block getBlock(int id) {
        return blocks.get(id);
    }
}
