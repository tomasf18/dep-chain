package ist.depchain.tests.stage2;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test through full HotStuff consensus: client1 transfers most
 * of its balance to client2, then attempts a second transfer that should fail
 * because the remaining balance is insufficient.
 *
 * Uses client1 (sender) and client2 (receiver) - both are real configured
 * clients with keystores and genesis-funded accounts.
 *
 * The test snapshots the actual balance from the world state and computes
 * transfer amounts relative to it, so it is independent of genesis values.
 *
 * Scenario (with actual genesis balance B for client1):
 *   - Transfer 1: (B - 163_000) to client2 -> succeeds (remaining = 100_000 after gas)
 *   - Transfer 2: 50_000 to client2        -> fails    (needs 113_000 but only 100_000 left)
 */
class InsufficientBalanceAfterTransferTest {

    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    private static final String CLIENT2_ADDR = "0x172bf398d2a931323199521625f471fb1c28879a";

    // Gas price of 3 ensures a single tx fee (3 * 21_000 = 63_000) meets the
    // min_fee_threshold (63_000) so the block is proposed immediately.
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger GAS_FEE   = GAS_PRICE.multiply(GAS_LIMIT); // 63_000

    private ClientContext clientContext;
    private MessageHandler messageHandler;
    private ClientLibrary clientLibrary;

    @BeforeEach
    void setup() {
        System.out.println("[TEST] Starting InsufficientBalanceAfterTransferTest");

        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        System.out.println("[TEST] Waiting for replica handshake...");
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        messageHandler = new MessageHandler(clientContext);
        clientLibrary = new ClientLibrary(clientContext, messageHandler);

        Address clientAddress = clientContext.getSelfAddress();
        System.out.println("[TEST] Client address: " + clientAddress.toHexString());

        Address receiver = Address.fromHexString(CLIENT2_ADDR);

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord, "Coordinator for " + replicaId + " should be registered");

            DepChainWorldState ws = coord.getServerContext().getWorldState();

            // Ensure both accounts exist (they should from genesis)
            if (!ws.accountExists(clientAddress)) {
                ws.createEOA(clientAddress, 0, BigInteger.valueOf(10_000_000));
            }
            if (!ws.accountExists(receiver)) {
                ws.createEOA(receiver, 0, BigInteger.ZERO);
            }
        }

        clientContext.start();
    }

    @AfterEach
    void teardown() {
        if (clientContext != null) {
            clientContext.stop();
        }
        System.out.println("[TEST] Ending InsufficientBalanceAfterTransferTest");
    }

    @Test
    @DisplayName("Second transfer fails when balance depleted by first transfer")
    void secondTransferRejectedWhenBalanceDepleted() {
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));

        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString(CLIENT2_ADDR);

        // Snapshot actual balances (includes genesis funding)
        DepChainWorldState wsSnap = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        BigInteger initialSenderBalance = wsSnap.getBalance(sender);
        BigInteger initialReceiverBalance = wsSnap.getBalance(receiver);

        System.out.println("[TEST] Initial sender balance: " + initialSenderBalance);
        System.out.println("[TEST] Initial receiver balance: " + initialReceiverBalance);

        // First transfer: drain almost everything.
        // Reserve enough for gas fee (63_000) + a small remainder (100_000).
        // After tx1: remaining = initialBalance - firstTransfer - gasFee = 100_000
        BigInteger reserve = GAS_FEE.add(BigInteger.valueOf(100_000)); // 163_000
        BigInteger firstTransfer = initialSenderBalance.subtract(reserve);
        assertTrue(firstTransfer.signum() > 0, "sender balance too low for this test");

        // Second transfer: 50_000 -> needs 50_000 + 63_000 = 113_000 > 100_000 remaining
        BigInteger secondTransfer = BigInteger.valueOf(50_000);

        // --- First transfer (should succeed) ---
        System.out.println("[TEST] Submitting first transfer (value=" + firstTransfer + ")");
        clientLibrary.submitNativeTransfer(CLIENT2_ADDR, firstTransfer, GAS_PRICE, GAS_LIMIT);
        System.out.println("[TEST] First transfer accepted");

        // --- Second transfer (should fail - insufficient balance) ---
        System.out.println("[TEST] Submitting second transfer (value=" + secondTransfer + ")");
        try {
            clientLibrary.submitNativeTransfer(CLIENT2_ADDR, secondTransfer, GAS_PRICE, GAS_LIMIT);
            System.out.println("[TEST] Second transfer accepted (will fail during execution)");
        } catch (RuntimeException e) {
            System.out.println("[TEST] Second transfer rejected at validation: " + e.getMessage());
        }

        // --- Expected state: only first transfer committed ---
        BigInteger expectedSenderBalance = initialSenderBalance
                .subtract(firstTransfer)
                .subtract(GAS_FEE);  // 50_000 - 21_000 = 29_000
        BigInteger expectedReceiverBalance = initialReceiverBalance
                .add(firstTransfer);

        System.out.println("[TEST] Expected sender balance: " + expectedSenderBalance);
        System.out.println("[TEST] Expected receiver balance: " + expectedReceiverBalance);

        waitForReplicaConvergence(sender, receiver,
                expectedSenderBalance, expectedReceiverBalance,
                TimeUnit.SECONDS.toMillis(120));

        // --- Verify across all replicas ---
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId)
                    .getServerContext().getWorldState();

            assertEquals(expectedSenderBalance, ws.getBalance(sender),
                    "sender balance mismatch on " + replicaId +
                            " - second transfer should NOT have gone through");
            assertEquals(expectedReceiverBalance, ws.getBalance(receiver),
                    "receiver balance mismatch on " + replicaId +
                            " - should only reflect first transfer");
        }

        System.out.println("[TEST] Verified: only first transfer committed." +
                " Sender balance: " + expectedSenderBalance +
                ", Receiver balance: " + expectedReceiverBalance);
    }

    // ========== Helpers ==========

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, serverId, "false"});
            } catch (Exception e) {
                System.err.println("[TEST] Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void waitForReplicaConvergence(Address sender, Address receiver,
                                           BigInteger expectedSenderBalance,
                                           BigInteger expectedReceiverBalance,
                                           long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            String referenceBlockHash = null;

            for (String replicaId : REPLICAS) {
                BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
                DepChainWorldState ws = coord.getServerContext().getWorldState();

                if (!ws.getBalance(sender).equals(expectedSenderBalance)
                        || !ws.getBalance(receiver).equals(expectedReceiverBalance)) {
                    allMatch = false;
                    break;
                }

                String blockHash = coord.getServerContext().getBlockChain()
                        .getLatestBlock().getBlockHash();
                if (referenceBlockHash == null) {
                    referenceBlockHash = blockHash;
                } else if (!referenceBlockHash.equals(blockHash)) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                return;
            }

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId)
                    .getServerContext().getWorldState();
            System.out.println("[TEST] " + replicaId + ": sender=" + ws.getBalance(sender)
                    + ", receiver=" + ws.getBalance(receiver));
        }

        fail("State did not converge within " + timeoutMs + "ms." +
                " Expected sender=" + expectedSenderBalance +
                ", receiver=" + expectedReceiverBalance);
    }
}
