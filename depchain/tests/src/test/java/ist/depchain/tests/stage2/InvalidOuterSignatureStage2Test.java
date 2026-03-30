package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
 * Invalid outer client-request signature tests (TODO-TESTS §B).
 *
 * Guarantee: unauthorized clients cannot modify account state. The outer
 * request envelope signature (distinct from the inner tx signature) is
 * verified by the server's MessageHandler before the request reaches
 * the mempool or consensus.
 *
 * Tests:
 *   1. Forged outer signature (request signed with a random key, not client1's)
 *      → servers silently drop the request; no state change.
 *   2. Missing outer signature (empty signature field)
 *      → servers drop the request.
 *   3. Tampered request body (valid signature over original body, but body
 *      modified after signing) → servers drop the request.
 *   4. Valid inner tx but spoofed clientId (attacker uses own key to sign
 *      but claims to be "client1") → servers reject because the signature
 *      does not verify against client1's public key.
 */
class InvalidOuterSignatureStage2Test {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    private static final BigInteger GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger TRANSFER_VALUE = BigInteger.valueOf(100);

    private ClientContext clientContext;
    private ClientLibrary clientLibrary;
    private MessageHandler clientMessageHandler;

    @BeforeEach
    void setup() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config clientConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext = new ClientContext(clientConfig);
        clientMessageHandler = new MessageHandler(clientContext);
        clientLibrary = new ClientLibrary(clientContext, clientMessageHandler);

        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord);
            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(sender)) ws.createEOA(sender, 0, BigInteger.valueOf(10_000_000));
            if (!ws.accountExists(receiver)) ws.createEOA(receiver, 0, BigInteger.ZERO);
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
     * Outer request envelope signed with a random key (not client1's registered key).
     * The server verifies the outer signature against client1's trusted public key
     * and must reject this request.
     */
    @Test
    void forgedOuterSignatureIsDroppedByServers() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        BigInteger initialReceiverBalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(receiver);

        // Build a valid transaction signed with client1's key
        Transaction unsignedTx = new Transaction(sender, receiver, TRANSFER_VALUE, new byte[0],
                GAS_PRICE, GAS_LIMIT, clientContext.getNonce(), null);
        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        // Build client request but sign the outer envelope with a RANDOM key
        KeyPair forgerKeys = genKeyPair();
        ClientRequest unsignedReq = ClientRequest.newBuilder()
                .setClientId("client1")
                .setRequestId(500)
                .setTransaction(signedTx.toProto())
                .build();

        byte[] forgedSig = Crypto.sign(unsignedReq.toByteArray(), forgerKeys.getPrivate(),
                clientContext.getConfig().getSignatureAlgorithm());
        ClientRequest forgedReq = ClientRequest.newBuilder(unsignedReq)
                .setSignature(ByteString.copyFrom(forgedSig))
                .build();

        broadcastRequest(500, forgedReq, "forged outer sig");

        // Wait and verify: no state change
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(initialReceiverBalance, ws.getBalance(receiver),
                    "forged outer sig should not change receiver balance on " + replicaId);
        }
    }

    /**
     * Outer request with empty/missing signature field.
     */
    @Test
    void missingOuterSignatureIsDroppedByServers() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        BigInteger initialReceiverBalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(receiver);

        Transaction unsignedTx = new Transaction(sender, receiver, TRANSFER_VALUE, new byte[0],
                GAS_PRICE, GAS_LIMIT, clientContext.getNonce(), null);
        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        // No outer signature at all
        ClientRequest unsignedReq = ClientRequest.newBuilder()
                .setClientId("client1")
                .setRequestId(501)
                .setTransaction(signedTx.toProto())
                .build();

        broadcastRequest(501, unsignedReq, "missing outer sig");

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(initialReceiverBalance, ws.getBalance(receiver),
                    "missing outer sig should not change receiver balance on " + replicaId);
        }
    }

    /**
     * Valid outer signature over original request body, but the body is modified
     * after signing (requestId changed). Signature won't verify.
     */
    @Test
    void tamperedRequestBodyIsDroppedByServers() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        BigInteger initialReceiverBalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(receiver);

        Transaction unsignedTx = new Transaction(sender, receiver, TRANSFER_VALUE, new byte[0],
                GAS_PRICE, GAS_LIMIT, clientContext.getNonce(), null);
        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        // Sign the request with the correct key
        ClientRequest originalReq = ClientRequest.newBuilder()
                .setClientId("client1")
                .setRequestId(502)
                .setTransaction(signedTx.toProto())
                .build();

        byte[] validSig = Crypto.sign(originalReq.toByteArray(), clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        // Tamper: change the requestId but keep the original signature
        ClientRequest tamperedReq = ClientRequest.newBuilder()
                .setClientId("client1")
                .setRequestId(999)
                .setTransaction(signedTx.toProto())
                .setSignature(ByteString.copyFrom(validSig))
                .build();

        broadcastRequest(999, tamperedReq, "tampered body");

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(initialReceiverBalance, ws.getBalance(receiver),
                    "tampered request body should not change receiver balance on " + replicaId);
        }
    }

    /**
     * Attacker uses their own key to sign the request but claims clientId = "client1".
     * The server looks up client1's trusted public key, which won't match the
     * attacker's signature → request dropped.
     */
    @Test
    void spoofedClientIdIsDroppedByServers() throws Exception {
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        BigInteger initialReceiverBalance = ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(receiver);

        // Attacker generates their own keys
        KeyPair attackerKeys = genKeyPair();
        Address attackerAddress = clientContext.getSelfAddress(); // pretends to be client1

        Transaction unsignedTx = new Transaction(attackerAddress, receiver, TRANSFER_VALUE, new byte[0],
                GAS_PRICE, GAS_LIMIT, 0, null);
        Transaction signedTx = TransactionSigner.sign(unsignedTx, attackerKeys.getPrivate(),
                clientContext.getConfig().getSignatureAlgorithm());

        // Sign outer envelope with attacker's key but claim to be client1
        ClientRequest unsignedReq = ClientRequest.newBuilder()
                .setClientId("client1")
                .setRequestId(503)
                .setTransaction(signedTx.toProto())
                .build();

        byte[] attackerSig = Crypto.sign(unsignedReq.toByteArray(), attackerKeys.getPrivate(),
                clientContext.getConfig().getSignatureAlgorithm());
        ClientRequest spoofedReq = ClientRequest.newBuilder(unsignedReq)
                .setSignature(ByteString.copyFrom(attackerSig))
                .build();

        broadcastRequest(503, spoofedReq, "spoofed clientId");

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            assertEquals(initialReceiverBalance, ws.getBalance(receiver),
                    "spoofed clientId should not change receiver balance on " + replicaId);
        }
    }

    // ==================== Helpers ====================

    private void broadcastRequest(int requestId, ClientRequest signedRequest, String description) {
        clientContext.registerRequestInMap(requestId, description);
        clientMessageHandler.getPendingRequests().put(requestId, new ConcurrentHashMap<>());
        clientMessageHandler.registerFuture(requestId);

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(signedRequest)
                .build();

        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, appMsg.toByteArray());
    }

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
