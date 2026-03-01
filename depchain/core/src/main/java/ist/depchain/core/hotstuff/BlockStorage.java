package ist.depchain.core.hotstuff;
// package ist.depchain.core;

import com.google.protobuf.ByteString;
import ist.depchain.common.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockStorage {
    private final Map<ByteString, Block> blocks = new ConcurrentHashMap<>();

    public BlockStorage() {
        Block genesisBlock = Block.newBuilder()
                .setId(ByteString.EMPTY)
                .setParentId(ByteString.EMPTY)
                .build();
        this.putBlock(genesisBlock);
    }

    public void putBlock(Block block) {
         blocks.put(block.getId(), block);
    }
    public Block getBlock(ByteString id) {
        return blocks.get(id);
    }
}
