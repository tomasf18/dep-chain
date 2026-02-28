package ist.depchain.core;

import com.google.protobuf.ByteString;
import ist.depchain.common.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockStorage {
    private final Map<ByteString, Block> blocks = new ConcurrentHashMap<>();

    public void putBlock(Block block) {
        blocks.put(block.getHash(), block);
    }
    public Block getBlock(ByteString hash) {
        return blocks.get(hash);
    }
}
