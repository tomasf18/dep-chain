package ist.depchain.tests.stage2.integration;

import ist.depchain.client.ClientContext;
import ist.depchain.client.MessageHandler;
import ist.depchain.client.ClientLibrary;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.tests.stage2.Stage2GasConstants;
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
 * Integration test: submits multiple native DepCoin transfers through the full
 * BasicHotStuff consensus (4 replicas, 4 HotStuff phases) and verifies that
 * all transfers are committed and the receiver balance is correct.
 *
 * This test exercises the complete Stage 2 pipeline end-to-end:
 * Client -> MessageHandler (validate tx) -> Mempool -> doPropose (batch)
 * -> PREPARE -> PRE-COMMIT -> COMMIT -> DECIDE -> executeStage2Block
 * -> TransactionExecutor -> BlockChain persist -> ClientResponse
 */
class NativeTransferHotStuffTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };

    private static final String RECEIVER_HEX = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = Stage2GasConstants.NATIVE_TRANSFER_GAS_COST;

    private ClientContext clientContext;
    private MessageHandler messageHandler;
    private ClientLibrary clientLibrary;

    @BeforeEach
    void setup() {
        System.out.println("[TEST] Starting NativeTransferHotStuffTest");

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

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord, "Coordinator for " + replicaId + " should be registered");

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(clientAddress)) {
                ws.createEOA(clientAddress, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(clientAddress, BigInteger.valueOf(10_000_000));
            }
        }

        Address receiver = Address.fromHexString(RECEIVER_HEX);
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            if (!ws.accountExists(receiver)) {
                ws.createEOA(receiver, 0, BigInteger.ZERO);
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        clientContext.setRequestId((int) requestBase);
        clientContext
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(clientAddress));

        clientContext.start();
    }

    @AfterEach
    void teardown() {
        if (clientContext != null) {
            clientContext.stop();
        }
        stopReplicas();
        System.out.println("[TEST] Ending NativeTransferHotStuffTest");
    }

    @Test
    @DisplayName("Three sequential native transfers - each blocks until committed")
    void testMultipleSequentialTransfers() {
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));

        int numTransfers = 4;
        long totalValue = 0;
        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString(RECEIVER_HEX);
        DepChainWorldState initialState = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        BigInteger initialSenderBalance = initialState.getBalance(sender);
        BigInteger initialReceiverBalance = initialState.getBalance(receiver);

        for (int i = 0; i < numTransfers; i++) {
            BigInteger value = BigInteger.valueOf(100L * (i + 1));
            totalValue += 100L * (i + 1);
            System.out.println("[TEST] Submitting transfer " + (i + 1) + " (value=" + value + ")");
            // Blocking - returns only after f+1 commit responses; nonce auto-increments
            clientLibrary.submitNativeTransfer(RECEIVER_HEX, value, GAS_PRICE, GAS_LIMIT);
            System.out.println("[TEST] Transfer " + (i + 1) + " confirmed");
        }

        BigInteger expectedSenderBalance = waitForReplicaConvergence(sender, receiver,
                initialSenderBalance, initialReceiverBalance, totalValue, numTransfers,
                TimeUnit.SECONDS.toMillis(120));

        BigInteger expectedReceiverBalance = BigInteger.valueOf(totalValue);
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedSenderBalance, ws.getBalance(sender),
                    "sender balance mismatch on " + replicaId);
            assertEquals(expectedReceiverBalance, ws.getBalance(receiver),
                    "receiver balance mismatch on " + replicaId);
        }

        System.out.println("[TEST] All " + numTransfers + " native transfers committed. Sender balance: "
                + expectedSenderBalance + ", Receiver balance: " + expectedReceiverBalance);
    }

    // ========== Helpers ==========

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[] { CONFIG_FILE, serverId, "false" });
            } catch (Exception e) {
                System.err.println("[TEST] Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void stopReplicas() {
        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            if (coord == null) {
                continue;
            }

            try {
                coord.getServerContext().stop();
            } catch (Exception e) {
                System.err.println("[TEST] Error stopping replica context " + replicaId + ": " + e.getMessage());
            }
            coord.stop();
        }
    }

    private static BigInteger waitForReplicaConvergence(Address sender, Address receiver,
            BigInteger initialSenderBalance,
            BigInteger initialReceiverBalance,
            long totalValue, int numTransfers, long timeoutMs) {
        BigInteger expectedReceiverBalance = initialReceiverBalance.add(BigInteger.valueOf(totalValue));
        BigInteger gasFee = GAS_PRICE.multiply(GAS_LIMIT);
        BigInteger expectedSenderBalance = initialSenderBalance.subtract(BigInteger.valueOf(totalValue))
                .subtract(gasFee.multiply(BigInteger.valueOf(numTransfers)));

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

                String blockHash = coord.getServerContext().getBlockChain().getLatestBlock().getBlockHash();
                if (referenceBlockHash == null) {
                    referenceBlockHash = blockHash;
                } else if (!referenceBlockHash.equals(blockHash)) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                return expectedSenderBalance;
            }

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }

        DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        fail("Native transfer state did not converge within " + timeoutMs + "ms; sender="
                + ws.getBalance(sender) + ", receiver=" + ws.getBalance(receiver));
        return initialSenderBalance;
    }
}
