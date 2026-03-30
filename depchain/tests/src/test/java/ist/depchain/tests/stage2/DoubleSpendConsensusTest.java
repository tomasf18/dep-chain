package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.google.protobuf.ByteString;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.utils.TransactionSigner;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

/**
 * Double-spend prevention tests through full HotStuff consensus (TODO-TESTS §D).
 *
 * Guarantee: a client cannot spend the same funds twice.
 *
 * Tests:
 *   1. Two transactions with the same nonce to different receivers —
 *      only one can commit; the other is rejected as a duplicate nonce.
 *   2. Two sequential transactions whose combined value exceeds balance —
 *      only the first commits; the second fails at execution time.
 *   3. Same-nonce conflicting spends submitted rapidly —
 *      at most one commits across all replicas.
 */
class DoubleSpendConsensusTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    private static final BigInteger GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger GAS_FEE = GAS_PRICE.multiply(Stage2GasConstants.NATIVE_TRANSFER_GAS_COST);
    private static final BigInteger TRANSFER_VALUE = BigInteger.valueOf(100);
    private static final Address RECEIVER_A = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address RECEIVER_B = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;
    private MessageHandler messageHandler;

    @BeforeEach
    void setup() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        messageHandler = new MessageHandler(clientContext);
        clientLibrary = new ClientLibrary(clientContext, messageHandler);

        Address sender = clientContext.getSelfAddress();

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord);

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(sender)) {
                ws.createEOA(sender, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(sender, BigInteger.valueOf(10_000_000));
            }
            if (!ws.accountExists(RECEIVER_A)) ws.createEOA(RECEIVER_A, 0, BigInteger.ZERO);
            if (!ws.accountExists(RECEIVER_B)) ws.createEOA(RECEIVER_B, 0, BigInteger.ZERO);
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        clientContext.setRequestId((int) requestBase);
        clientContext.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(sender));

        clientContext.start();
    }

    @AfterEach
    void teardown() {
        if (clientContext != null) clientContext.stop();
        stopReplicas();
    }

    /**
     * Two transactions with the same nonce sent to different receivers.
     * Only one can possibly commit — the duplicate nonce is rejected.
     * Verifies that at most one receiver has a non-zero balance.
     */
    @Test
    void sameNonceConflictingTransfersOnlyOneCommits() throws Exception {
        Address sender = clientContext.getSelfAddress();
        long nonce = clientContext.getNonce();

        // Build two conflicting txs with the same nonce
        ClientRequest reqA = buildSignedRequest(100, sender, RECEIVER_A, nonce);
        ClientRequest reqB = buildSignedRequest(101, sender, RECEIVER_B, nonce);

        BigInteger initialReceiverABalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(RECEIVER_A);
        BigInteger initialReceiverBBalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(RECEIVER_B);

        // Submit both in rapid succession
        submitRawRequest(100, reqA, "double-spend A");
        submitRawRequest(101, reqB, "double-spend B");

        // Wait for at most one to commit
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(30));

        // Verify across all replicas: at most one receiver got funds
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            BigInteger receiverAGain = ws.getBalance(RECEIVER_A).subtract(initialReceiverABalance);
            BigInteger receiverBGain = ws.getBalance(RECEIVER_B).subtract(initialReceiverBBalance);

            // At most one receiver should have received funds
            assertFalse(
                    receiverAGain.signum() > 0 && receiverBGain.signum() > 0,
                    "double spend on " + replicaId + ": both receivers got funds (A=" + receiverAGain + ", B=" + receiverBGain + ")"
            );
        }

        // All replicas must agree on state
        assertReplicaStateHashesMatch();
    }

    /**
     * Two sequential transactions whose combined value exceeds the sender's
     * balance. The first transfer should succeed; the second should fail
     * at execution (insufficient balance for upfront cost).
     */
    @Test
    void sequentialTransfersExceedingBalanceOnlyFirstCommits() {
        Address sender = clientContext.getSelfAddress();
        BigInteger senderBalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(sender);

        // Upfront gas reservation = gasPrice * gasLimit (not actual fee)
        BigInteger upfrontGas = GAS_PRICE.multiply(GAS_LIMIT); // 3 * 21_000 = 63_000
        // First transfer: drain almost everything, leaving only 1_000 after upfront cost
        BigInteger firstTransfer = senderBalance.subtract(upfrontGas).subtract(BigInteger.valueOf(1_000));
        assertTrue(firstTransfer.signum() > 0, "sender balance too low for test");

        // Second transfer: 500 — needs 500 + 63_000 upfront = 63_500, but only ~4_000 remains
        // (1_000 leftover + 3_000 gas refund from first tx)
        BigInteger secondTransfer = BigInteger.valueOf(500);

        clientLibrary.submitNativeTransfer(RECEIVER_A.toHexString(), firstTransfer, GAS_PRICE, GAS_LIMIT);

        // Second transfer: may be rejected at validation or execution
        try {
            clientLibrary.submitNativeTransfer(RECEIVER_B.toHexString(), secondTransfer, GAS_PRICE, GAS_LIMIT);
        } catch (RuntimeException ignored) {
            // Expected: rejected at validation time
        }

        // Wait for state to settle
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            boolean converged = true;
            for (String replicaId : REPLICAS) {
                DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
                if (!ws.getBalance(RECEIVER_A).equals(firstTransfer)) {
                    converged = false;
                    break;
                }
            }
            if (converged) break;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }

        // Receiver B must NOT have received anything
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(BigInteger.ZERO, ws.getBalance(RECEIVER_B),
                    "receiver B got funds despite insufficient sender balance on " + replicaId);
        }

        assertReplicaStateHashesMatch();
    }

    /**
     * Rapidly submit the same signed transaction under three different request
     * IDs. Only one should commit (the nonce is consumed by the first).
     */
    @Test
    void rapidFireSameNonceOnlyOneCommits() throws Exception {
        Address sender = clientContext.getSelfAddress();
        long nonce = clientContext.getNonce();
        BigInteger initialReceiverBalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(RECEIVER_A);

        Transaction unsignedTx = new Transaction(
                sender, RECEIVER_A, TRANSFER_VALUE, new byte[0],
                GAS_PRICE, GAS_LIMIT, nonce, null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        // Fire three requests with the same signed tx but different request IDs
        for (int i = 0; i < 3; i++) {
            ClientRequest req = signClientRequest(200 + i, signedTx);
            submitRawRequest(200 + i, req, "rapid-fire-" + i);
        }

        // Wait for at most one commit
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(30));

        // Verify receiver got exactly one transfer worth
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            BigInteger receiverGain = ws.getBalance(RECEIVER_A).subtract(initialReceiverBalance);
            assertTrue(receiverGain.compareTo(TRANSFER_VALUE) <= 0,
                    "receiver on " + replicaId + " got " + receiverGain + " but should get at most " + TRANSFER_VALUE);
        }

        assertReplicaStateHashesMatch();
    }

    // ==================== Helpers ====================

    private void submitRawRequest(int requestId, ClientRequest signedRequest, String description) {
        clientContext.registerRequestInMap(requestId, description);
        messageHandler.getPendingRequests().put(requestId, new ConcurrentHashMap<>());
        messageHandler.registerFuture(requestId);

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(signedRequest)
                .build();

        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, appMsg.toByteArray());
    }

    private ClientRequest buildSignedRequest(int requestId, Address sender, Address receiver, long nonce) throws Exception {
        Transaction unsignedTx = new Transaction(
                sender, receiver, TRANSFER_VALUE, new byte[0],
                GAS_PRICE, GAS_LIMIT, nonce, null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        return signClientRequest(requestId, signedTx);
    }

    private ClientRequest signClientRequest(int requestId, Transaction signedTx) throws Exception {
        ClientRequest unsignedReq = ClientRequest.newBuilder()
                .setClientId("client1")
                .setRequestId(requestId)
                .setTransaction(signedTx.toProto())
                .build();

        byte[] sig = Crypto.sign(unsignedReq.toByteArray(), clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        return ClientRequest.newBuilder(unsignedReq)
                .setSignature(ByteString.copyFrom(sig))
                .build();
    }

    private static void assertReplicaStateHashesMatch() {
        String referenceHash = null;
        for (String replicaId : REPLICAS) {
            String stateHash = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState().computeStateHash();
            if (referenceHash == null) {
                referenceHash = stateHash;
            } else {
                assertEquals(referenceHash, stateHash, "State hash mismatch on " + replicaId);
            }
        }
    }

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

    private static void stopReplicas() {
        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            if (coord == null) continue;
            try { coord.getServerContext().stop(); } catch (Exception ignored) {}
            coord.stop();
        }
    }
}
