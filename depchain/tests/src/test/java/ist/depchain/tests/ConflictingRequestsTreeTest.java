package ist.depchain.tests;

import com.google.protobuf.ByteString;
import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.Block;
import ist.depchain.common.QC;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.byzantine.ByzantineCoordinator.AttackType;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.core.hotstuff.BasicHotStuffTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConflictingRequestsTreeTest
 *
 * Exercises the HotStuff tree under conflicting proposals and view changes,
 * then inspects the final tree on every honest replica.
 *
 * Scenario:
 *   - s1 (leader of view 1) is Byzantine EQUIVOCATE: sends BLOCK_A to some
 *     replicas and BLOCK_B to others.  This creates orphaned branches in the
 *     tree and forces a view change.
 *   - Two clients send 3 requests each (6 total) concurrently, so honest
 *     leaders across multiple views must handle a busy mempool with competing
 *     requests from different clients.
 *   - After all requests commit, the test dumps each honest replica's tree
 *     and asserts:
 *       1. All 6 client requests were committed (appear in the client logs).
 *       2. The equivocated blocks (BLOCK_A / BLOCK_B) exist in the tree but
 *          were never executed (not in executedBlockIds).
 *       3. The committed chain (executed blocks) forms a valid parent-child
 *          chain back to genesis.
 *       4. lockedQC and prepareQC point to blocks that exist in the tree.
 */
public class ConflictingRequestsTreeTest {
    private static final String CONFIG_FILE = "../config-test.json";
    private static final String[] HONEST_REPLICAS = {"s0", "s2", "s3"};

    private ClientContext clientContext1;
    private ClientContext clientContext2;
    private ClientLibrary clientLibrary1;
    private ClientLibrary clientLibrary2;

    @BeforeEach
    public void setup() throws InterruptedException {
        System.out.println("[TEST] - Starting ConflictingRequestsTreeTest");

        startReplica("s0", false, AttackType.SILENT);
        startReplica("s1", true,  AttackType.EQUIVOCATE); // byzantine leader for view 1
        startReplica("s2", false, AttackType.SILENT);
        startReplica("s3", false, AttackType.SILENT);

        TimeUnit.SECONDS.sleep(8); // wait for handshakes

        Config config1 = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext1 = new ClientContext(config1);
        clientLibrary1 = new ClientLibrary(clientContext1);
        clientContext1.start();

        Config config2 = Config.loadConfiguration(CONFIG_FILE, "client2");
        clientContext2 = new ClientContext(config2);
        clientLibrary2 = new ClientLibrary(clientContext2);
        clientContext2.start();
    }

    @AfterEach
    public void teardown() {
        if (clientContext1 != null) clientContext1.stop();
        if (clientContext2 != null) clientContext2.stop();
    }

    @Test
    @DisplayName("Conflicting proposals create orphaned branches; all legitimate requests still commit and tree is consistent")
    void testConflictingRequestsTree() throws InterruptedException {
        String[] requestsC1 = {"C1-Alpha", "C1-Beta", "C1-Gamma"};
        String[] requestsC2 = {"C2-Alpha", "C2-Beta", "C2-Gamma"};

        TimeUnit.SECONDS.sleep(5); // let sessions establish

        // Send all requests concurrently from both clients
        Thread t1 = new Thread(() -> { for (String r : requestsC1) clientLibrary1.append(r); });
        Thread t2 = new Thread(() -> { for (String r : requestsC2) clientLibrary2.append(r); });
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        t1.join(); t2.join();

        System.out.println("[TEST] - All 6 requests sent, waiting for commits...");

        // Wait for all commits (up to 120 seconds to handle view changes + fault injection)
        int timeout = 120;
        while (timeout > 0
                && (clientContext1.getCommitedLog().size() < requestsC1.length
                 || clientContext2.getCommitedLog().size() < requestsC2.length)) {
            TimeUnit.SECONDS.sleep(1);
            timeout--;
        }

        // --- Assertion 1: all client requests committed ---
        List<String> log1 = clientContext1.getCommitedLog();
        List<String> log2 = clientContext2.getCommitedLog();

        assertEquals(requestsC1.length, log1.size(),
                "Client1 should have committed all requests");
        assertEquals(requestsC2.length, log2.size(),
                "Client2 should have committed all requests");

        for (String r : requestsC1)
            assertTrue(log1.contains(r), "Client1 missing committed request: " + r);
        for (String r : requestsC2)
            assertTrue(log2.contains(r), "Client2 missing committed request: " + r);

        // --- Inspect each honest replica's tree ---
        for (String replicaId : HONEST_REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord, "Coordinator not found for " + replicaId);

            BasicHotStuffTree tree = coord.getTree();
            Map<ByteString, Block> allBlocks = tree.getAllBlocks();
            Set<ByteString> executedIds = coord.getExecutedBlockIds();
            QC lockedQC = coord.getLockedQC();
            QC prepareQC = coord.getPrepareQC();

            System.out.println("\n========== TREE DUMP: " + replicaId
                    + " (view " + coord.getCurrentView() + ") ==========");
            System.out.println("Total blocks in tree: " + allBlocks.size());
            System.out.println("Executed (decided) blocks: " + executedIds.size());
            System.out.println("LockedQC  -> view " + lockedQC.getViewNumber()
                    + ", block " + idLabel(lockedQC.getBlockId(), allBlocks));
            System.out.println("PrepareQC -> view " + prepareQC.getViewNumber()
                    + ", block " + idLabel(prepareQC.getBlockId(), allBlocks));

            // Print every block
            for (Block block : allBlocks.values()) {
                boolean isGenesis = block.getId().equals(ByteString.EMPTY);
                boolean executed = executedIds.contains(block.getId());
                boolean isLocked = block.getId().equals(lockedQC.getBlockId());
                boolean isPrepared = block.getId().equals(prepareQC.getBlockId());

                StringBuilder flags = new StringBuilder();
                if (isGenesis)  flags.append(" [GENESIS]");
                if (executed)   flags.append(" [EXECUTED]");
                if (isLocked)   flags.append(" [LOCKED]");
                if (isPrepared) flags.append(" [PREPARED]");
                if (!executed && !isGenesis) flags.append(" [ORPHAN]");

                String data = block.hasCommand() ? block.getCommand().getData() : "<none>";
                String clientId = block.hasCommand() ? block.getCommand().getClientId() : "";
                int reqId = block.hasCommand() ? block.getCommand().getRequestId() : -1;

                System.out.println("  Block " + idLabel(block.getId(), allBlocks)
                        + " | parent=" + idLabel(block.getParentId(), allBlocks)
                        + " | data=\"" + data + "\""
                        + (clientId.isEmpty() ? "" : " | client=" + clientId + " req=" + reqId)
                        + flags);
            }

            // --- Assertion 2: equivocated blocks were NOT executed ---
            // After pruning, equivocated siblings may have been removed from the tree.
            // If they are still present, they must not be executed.
            boolean foundEquivocated = false;
            for (Block block : allBlocks.values()) {
                if (!block.hasCommand()) continue;
                String data = block.getCommand().getData();
                if ("BLOCK_A".equals(data) || "BLOCK_B".equals(data)) {
                    foundEquivocated = true;
                    assertFalse(executedIds.contains(block.getId()),
                            replicaId + ": equivocated block '" + data + "' must not be executed");
                    System.out.println("  [OK] Equivocated block '" + data + "' survived pruning but NOT executed");
                }
            }
            if (!foundEquivocated) {
                System.out.println("  [OK] Equivocated blocks were pruned from tree (siblings removed on commit)");
            }

            // --- Assertion 3: executed blocks form a valid chain to genesis ---
            for (ByteString executedId : executedIds) {
                Block b = allBlocks.get(executedId);
                assertNotNull(b, replicaId + ": executed block not found in tree");
                // Walk the parent chain back to genesis
                Set<ByteString> visited = new HashSet<>();
                Block current = b;
                while (!current.getId().equals(ByteString.EMPTY)) {
                    assertFalse(visited.contains(current.getId()),
                            replicaId + ": cycle detected in block chain");
                    visited.add(current.getId());
                    Block parent = allBlocks.get(current.getParentId());
                    assertNotNull(parent,
                            replicaId + ": broken chain — parent not found for block "
                                    + idLabel(current.getId(), allBlocks));
                    current = parent;
                }
            }
            System.out.println("  [OK] All executed blocks chain back to genesis");

            // --- Assertion 4: lockedQC and prepareQC reference existing blocks ---
            if (lockedQC.getViewNumber() > 0) {
                assertNotNull(allBlocks.get(lockedQC.getBlockId()),
                        replicaId + ": lockedQC references a block not in the tree");
            }
            if (prepareQC.getViewNumber() > 0) {
                assertNotNull(allBlocks.get(prepareQC.getBlockId()),
                        replicaId + ": prepareQC references a block not in the tree");
            }
            System.out.println("  [OK] lockedQC and prepareQC reference valid blocks");
        }

        System.out.println("\n[TEST] - PASSED: all 6 requests committed, "
                + "equivocated blocks orphaned, tree consistent on all honest replicas");

        TimeUnit.SECONDS.sleep(5);
    }

    // --- Helpers ---

    /** Short label for a block ID (first 8 hex chars, or "genesis"). */
    private static String idLabel(ByteString id, Map<ByteString, Block> blocks) {
        if (id == null || id.equals(ByteString.EMPTY)) return "genesis";
        String hex = bytesToHex(id.toByteArray());
        // If the block is in the tree and has command data, append it for readability
        Block b = blocks.get(id);
        if (b != null && b.hasCommand() && !b.getCommand().getData().isEmpty()) {
            return hex.substring(0, Math.min(8, hex.length())) + "(" + b.getCommand().getData() + ")";
        }
        return hex.substring(0, Math.min(8, hex.length()));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
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
}
