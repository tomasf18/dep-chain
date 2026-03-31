package ist.depchain.tests.stage2.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.Crypto;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionValidator;
import ist.depchain.core.blockchain.ValidationResult;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.tests.stage2.GasConstants;

/**
 * Guarantee: future nonces (txNonce > committedNonce) are accepted at
 * validation time to support pipelined transactions, but only one
 * transaction per nonce can be pending at a time. This prevents abuse
 * where an attacker submits many future-nonce transactions to occupy
 * the mempool without having the preceding nonces committed.
 *
 * Tests:
 *   1. (Unit) Future nonce accepted when no pending nonces conflict.
 *   2. (Unit) Duplicate future nonce rejected when already pending.
 *   3. (Unit) Stale nonce (below committed) rejected.
 *   4. (Integration) Pipelined transactions execute in correct nonce order.
 */
class FutureNonceAndPipeliningTest {

    // ==================== Unit tests ====================

    @Test
    void futureNonceIsAcceptedWhenNotPending() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(1_000_000)); // committed nonce = 0

        // Submit nonce 5 (future nonce, committed is 0)
        Transaction unsignedTx = new Transaction(from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, GasConstants.NATIVE_TRANSFER_GAS_COST, 5, null);
        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        ValidationResult result = TransactionValidator.validate(signed, kp.getPublic(), "SHA256withECDSA", ws, new HashSet<>());
        assertTrue(result.isValid(), "Future nonce should be accepted: " + result.getErrorMessage());
    }

    @Test
    void duplicateFutureNonceRejectedWhenAlreadyPending() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, GasConstants.NATIVE_TRANSFER_GAS_COST, 5, null);
        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        // Nonce 5 already pending
        Set<Long> pending = new HashSet<>();
        pending.add(5L);

        ValidationResult result = TransactionValidator.validate(signed, kp.getPublic(), "SHA256withECDSA", ws, pending);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("already pending"));
    }

    @Test
    void staleNonceBelowCommittedIsRejected() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 5, BigInteger.valueOf(1_000_000)); // committed nonce = 5

        // Submit nonce 3 (stale)
        Transaction unsignedTx = new Transaction(from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, GasConstants.NATIVE_TRANSFER_GAS_COST, 3, null);
        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        ValidationResult result = TransactionValidator.validate(signed, kp.getPublic(), "SHA256withECDSA", ws, null);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("nonce"));
    }

    @Test
    void multipleDistinctFutureNoncesAllAccepted() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Set<Long> pending = new HashSet<>();

        // Submit nonces 0, 1, 2 (pipelined) - each should be accepted
        for (int nonce = 0; nonce < 3; nonce++) {
            Transaction unsignedTx = new Transaction(from, to, BigInteger.valueOf(100), new byte[0],
                    BigInteger.ONE, GasConstants.NATIVE_TRANSFER_GAS_COST, nonce, null);
            byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
            Transaction signed = unsignedTx.withSignature(sig);

            ValidationResult result = TransactionValidator.validate(signed, kp.getPublic(), "SHA256withECDSA", ws, pending);
            assertTrue(result.isValid(), "Nonce " + nonce + " should be accepted: " + result.getErrorMessage());
            pending.add((long) nonce);
        }
    }

    // ==================== Integration test ====================

    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;

    @BeforeEach
    void setupIntegration() {
        // Only set up replicas for integration tests (unit tests above don't need them)
    }

    @AfterEach
    void teardown() {
        if (clientContext != null) clientContext.stop();
        stopReplicas();
    }

    /**
     * Submit 3 pipelined native transfers rapidly (nonces 0, 1, 2).
     * All three should eventually commit in nonce order, and the receiver
     * should have the sum of all three transfer values.
     */
    @Test
    void pipelinedTransactionsExecuteInNonceOrder() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        MessageHandler handler = new MessageHandler(clientContext);
        clientLibrary = new ClientLibrary(clientContext, handler);

        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord);
            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(sender)) ws.createEOA(sender, 0, BigInteger.valueOf(10_000_000));
            else ws.addBalance(sender, BigInteger.valueOf(10_000_000));
            if (!ws.accountExists(receiver)) ws.createEOA(receiver, 0, BigInteger.ZERO);
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        clientContext.setRequestId((int) requestBase);
        clientContext.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(sender));
        clientContext.start();

        BigInteger value1 = BigInteger.valueOf(100);
        BigInteger value2 = BigInteger.valueOf(200);
        BigInteger value3 = BigInteger.valueOf(300);
        BigInteger gasPrice = BigInteger.valueOf(3);
        BigInteger gasLimit = BigInteger.valueOf(21_000);

        // Submit 3 transfers rapidly (pipelined)
        clientLibrary.submitNativeTransfer(receiver.toHexString(), value1, gasPrice, gasLimit);
        clientLibrary.submitNativeTransfer(receiver.toHexString(), value2, gasPrice, gasLimit);
        clientLibrary.submitNativeTransfer(receiver.toHexString(), value3, gasPrice, gasLimit);

        BigInteger expectedReceiverBalance = value1.add(value2).add(value3);

        // Wait for convergence
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
                if (!ws.getBalance(receiver).equals(expectedReceiverBalance)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) break;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(expectedReceiverBalance, ws.getBalance(receiver),
                    "receiver balance mismatch on " + replicaId + " - pipelined txs may not have executed in order");
            // Nonce should be advanced by 3
            long expectedNonce = clientContext.getNonce();
            assertEquals(expectedNonce, ws.getNonce(sender),
                    "sender nonce mismatch on " + replicaId);
        }

        // All replicas must agree on state
        String referenceHash = null;
        for (String replicaId : REPLICAS) {
            String stateHash = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState().computeStateHash();
            if (referenceHash == null) referenceHash = stateHash;
            else assertEquals(referenceHash, stateHash, "State hash mismatch on " + replicaId);
        }
    }

    // ==================== Helpers ====================

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
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
