package ist.depchain.core;

import java.util.ArrayList;
import java.util.List;

public class BlockChain {
    private List<String> blocks;

    public BlockChain() {
        this.blocks = new ArrayList<>();
    }

    public void appendBlock(String block) {
        blocks.add(block);
    }
}
