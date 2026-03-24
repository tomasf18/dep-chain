package ist.depchain.tests.stage1;

import com.google.protobuf.ByteString;
import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.Block;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.byzantine.ByzantineCoordinator.AttackType;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.core.hotstuff.BasicHotStuffTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress test for the tree pruning logic.
 *
 * Sends dozens of requests across multiple view changes (triggered by a
 * silent Byzantine leader) so that the tree accumulates branches from
 * failed views that must be pruned when commits happen.
 */
public class PruningStressTest {
    private static final String CONFIG_FILE = "../config-dev.json";
    private static final int NUM_REQUESTS = 30;

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting PruningStressTest");

        // s1 is the leader of view 1 - make it equivocate (propose diverging commands)
        // to force view changes and create competing branches that must be pruned
        startReplica("s0", false, AttackType.SILENT);
        startReplica("s1", true,  AttackType.EQUIVOCATE);
        startReplica("s2", false, AttackType.SILENT);
        startReplica("s3", false, AttackType.SILENT);

        System.out.println("[TEST] - Starting Client");
        Config config = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(config);
        clientLibrary = new ClientLibrary(clientContext);
        clientContext.start();
    }

    @AfterEach
    public void teardown() {
        System.out.println("[TEST] - Ending PruningStressTest");
        if (clientContext != null) {
            clientContext.stop();
        }
    }

    @Test
    @DisplayName("Dozens of requests across view changes stress the pruning logic")
    public void testPruningUnderLoad() throws InterruptedException {
        String[] requests = new String[NUM_REQUESTS];

        for (int i = 0; i < NUM_REQUESTS; i++) {
            requests[i] = "PruningStress-" + (i + 1);
            clientLibrary.append(requests[i]);
        }

        System.out.println("[TEST] - All " + NUM_REQUESTS + " requests submitted, waiting for commits...");

        boolean allCommitted = waitForCommit(NUM_REQUESTS, 300);
        assertTrue(allCommitted,
                "Not all requests committed within timeout. Committed: "
                        + clientContext.getCommitedLog().size() + "/" + NUM_REQUESTS);

        List<String> log = clientContext.getCommitedLog();
        assertEquals(NUM_REQUESTS, log.size(), "Committed log size mismatch");

        for (String request : requests) {
            assertTrue(log.contains(request), "Missing from committed log: " + request);
        }

        // Safety: equivocated blocks from the Byzantine leader must never be committed
        assertFalse(log.contains("BLOCK_A"), "Safety violation: equivocated BLOCK_A was committed");
        assertFalse(log.contains("BLOCK_B"), "Safety violation: equivocated BLOCK_B was committed");

        System.out.println("[TEST] - All " + NUM_REQUESTS + " requests committed successfully");
        clientLibrary.showLog();

        // Dump and verify that each honest replica's tree is a chain (no branching)
        for (String serverId : new String[]{"s0", "s2", "s3"}) {
            System.out.println("[TEST] - Tree dump for " + serverId + ":");
            BasicHotStuffCoordinator coordinator = ServerApp.getCoordinator(serverId);
            assertNotNull(coordinator, "Coordinator not found for " + serverId);
            printTree(serverId, coordinator.getTree());
            assertTreeIsChain(serverId);
        }
        System.out.println("[TEST] - All honest replicas have a linear chain (no branches)");
    }

    private void printTree(String serverId, BasicHotStuffTree tree) {
        Map<ByteString, Block> allBlocks = tree.getAllBlocks();
        // Build parentId -> children mapping from the flat blocks map
        Map<ByteString, List<Block>> childrenOf = new HashMap<>();
        for (Block b : allBlocks.values()) {
            if (!b.getId().equals(ByteString.EMPTY)) {
                childrenOf.computeIfAbsent(b.getParentId(), k -> new ArrayList<>()).add(b);
            }
        }

        System.out.println("=== TREE DUMP [" + serverId + "] === (total blocks: " + allBlocks.size() + ")");
        printSubtree(tree, childrenOf, ByteString.EMPTY, 0);
        System.out.println("=== END TREE ===");
    }

    private void printSubtree(BasicHotStuffTree tree, Map<ByteString, List<Block>> childrenOf,
                               ByteString id, int depth) {
        Block block = tree.getBlock(id);
        if (block == null) return;

        String indent = "  ".repeat(depth);
        String cmd = block.getCommand().getData();
        List<Block> children = childrenOf.getOrDefault(id, List.of());

        System.out.println(indent + "id=" + shortId(id)
                + " parent=" + shortId(block.getParentId())
                + " children=" + children.size()
                + " cmd=" + (cmd.isEmpty() ? "(genesis)" : cmd));

        for (Block child : children) {
            printSubtree(tree, childrenOf, child.getId(), depth + 1);
        }
    }

    private static String shortId(ByteString id) {
        if (id.isEmpty()) return "(empty)";
        String hex = id.toStringUtf8();
        return hex.length() > 8 ? hex.substring(0, 8) + "..." : hex;
    }

    private void assertTreeIsChain(String serverId) {
        BasicHotStuffCoordinator coordinator = ServerApp.getCoordinator(serverId);
        assertNotNull(coordinator, "Coordinator not found for " + serverId);

        BasicHotStuffTree tree = coordinator.getTree();
        Map<ByteString, Block> allBlocks = tree.getAllBlocks();

        // Build parentId -> children mapping from the flat blocks map
        Map<ByteString, List<Block>> childrenOf = new HashMap<>();
        for (Block b : allBlocks.values()) {
            if (!b.getId().equals(ByteString.EMPTY)) {
                childrenOf.computeIfAbsent(b.getParentId(), k -> new ArrayList<>()).add(b);
            }
        }

        // Every block must have at most 1 child (chain, not a tree)
        for (Map.Entry<ByteString, List<Block>> entry : childrenOf.entrySet()) {
            assertTrue(entry.getValue().size() <= 1,
                    serverId + ": block " + shortId(entry.getKey())
                            + " has " + entry.getValue().size() + " children - tree is not a chain");
        }

        // Walk from genesis to tip - every block must be reachable
        Block current = tree.getGenesisBlock();
        assertNotNull(current, serverId + ": genesis block missing");

        int walked = 1;
        while (true) {
            List<Block> children = childrenOf.getOrDefault(current.getId(), List.of());
            if (children.isEmpty()) break;
            current = children.get(0);
            walked++;
        }

        assertEquals(allBlocks.size(), walked,
                serverId + ": walked " + walked + " blocks but tree has " + allBlocks.size()
                        + " - orphan blocks exist");
    }

    private static void startReplica(String serverId, boolean isByzantine, AttackType attack) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, serverId,
                        String.valueOf(isByzantine), attack.toString()});
            } catch (Exception e) {
                System.out.println("[TEST] - Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private boolean waitForCommit(int expectedSize, int timeoutSeconds) throws InterruptedException {
        for (int i = 0; i < timeoutSeconds; i++) {
            if (clientContext.getCommitedLog().size() >= expectedSize) {
                return true;
            }
            TimeUnit.SECONDS.sleep(1);
        }
        return false;
    }
}
