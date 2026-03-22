package ist.depchain.core.hotstuff;

import com.google.protobuf.ByteString;
import ist.depchain.common.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BasicHotStuffTree {
    private final Map<ByteString, Block> blocks = new ConcurrentHashMap<>();

    public BasicHotStuffTree() {
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

    public Block getGenesisBlock() {
        return blocks.get(ByteString.EMPTY);
    }

    /** Remove all siblings of the committed block (same parentId, different id). */
    public void pruneSiblings(Block committed) {
        ByteString parentId = committed.getParentId();
        ByteString committedId = committed.getId();
        blocks.values().removeIf(b ->
                b.getParentId().equals(parentId)
                && !b.getId().equals(committedId)
                && !b.getId().equals(ByteString.EMPTY));
    }

    public Map<ByteString, Block> getAllBlocks() {
        return blocks;
    }
}
