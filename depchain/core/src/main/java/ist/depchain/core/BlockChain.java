package ist.depchain.core;

import java.util.ArrayList;
import java.util.List;

public class BlockChain {
    private final List<String> blocks;

    public BlockChain() {
        this.blocks = new ArrayList<>();
    }

    public void append(String block) {
        blocks.add(block);
    }

    public void showLog() {
        System.out.println("=== BlockChain Log ===");
        System.out.println("Total blocks: " + blocks.size());
        int i = 0;
        System.out.print(i + ": " + blocks.get(i));
        for (i = 1; i < blocks.size(); i++) {
            System.out.print(" <- " + i + ": " + blocks.get(i));
        }
        System.out.println("\n=====================");
    }
}
