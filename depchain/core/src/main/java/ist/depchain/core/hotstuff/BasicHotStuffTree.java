package ist.depchain.core.hotstuff;

import com.google.protobuf.ByteString;
import ist.depchain.common.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

        Map<ByteString, List<Block>> childrenOf = new HashMap<>();
        for (Block b : blocks.values()) {
            if (!b.getId().equals(ByteString.EMPTY)) {
                childrenOf.computeIfAbsent(b.getParentId(), k -> new ArrayList<>()).add(b);
            }
        }

        Block cur = blocks.get(ByteString.EMPTY);
        Block next = null;
        while(true) {
            boolean found = false;
            List<Block> toRemove = new ArrayList<>();
            for (Block b : childrenOf.get(cur.getId())) {
                if (childrenOf.getOrDefault(b.getId(), List.of()).isEmpty())
                    toRemove.add(b);
                else {
                    found = true;
                    next = b;
                }
            }
            if (!found) {
                break;
            }
            for (Block b: toRemove) {
                blocks.remove(b.getId());
            }
            cur = next;
        }

    }

    public Map<ByteString, Block> getAllBlocks() {
        pruneSiblings(null);
        return blocks;
    }
}
