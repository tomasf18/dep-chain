package ist.depchain.tests.stage2.integration;

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
 * Scenario: test that replaying the same client request (same request ID) or
 * the same signed transaction (same signature) through the consensus pipeline
 * does not result in duplicate commits or state changes.
 * Tests:
 * 1. Submit a client request that results in a committed block, then replay the
 * same request (same request ID and signature) - it should not create a second
 * committed log entry or change the state again.
 * 2. Submit a client request that results in a committed block, then submit a
 * new client request with the same signed transaction (same signature) but a
 * different request ID - it should also not create a second committed log entry
 * or change the state again.
 * 3. Submit a client request that results in a committed block, then submit a
 * new client request with the same signed transaction (same signature) but a
 * different request ID, and then submit another request with the same signed
 * transaction and the original request ID - only the first request should
 * commit, the others should be ignored.
 */
class RequestReplayThroughConsensusTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger TRANSFER_VALUE = BigInteger.valueOf(100);
    private static final Address SECOND_RECEIVER = Address.fromHexString("0x3333333333333333333333333333333333333333");

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
        Address receiver = clientConfig.getProcessInfo("client2").getAddress();

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(sender)) {
                ws.createEOA(sender, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(sender, BigInteger.valueOf(10_000_000));
            }
            if (!ws.accountExists(receiver)) {
                ws.createEOA(receiver, 0, BigInteger.ZERO);
            }
            if (!ws.accountExists(SECOND_RECEIVER)) {
                ws.createEOA(SECOND_RECEIVER, 0, BigInteger.ZERO);
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        clientContext.setRequestId((int) requestBase);
        clientContext.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(sender));

        clientContext.start();
    }

    @AfterEach
    void teardown() {
        if (clientContext != null) {
            clientContext.stop();
        }
        stopReplicas();
    }

    @Test
    void duplicateClientRequestReplayIsCommittedOnlyOnce() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address receiver = clientContext.getConfig().getProcessInfo("client2").getAddress();
        long nonce = clientContext.getNonce();

        clientLibrary.submitNativeTransfer(receiver.toHexString(), TRANSFER_VALUE, GAS_PRICE, GAS_LIMIT);

        waitForCommitLogSize(1, TimeUnit.SECONDS.toMillis(120));

        int requestId = clientContext.getRequestId().getAndIncrement();

        Transaction unsignedTx = new Transaction(
                sender,
                receiver,
                TRANSFER_VALUE,
                new byte[0],
                GAS_PRICE,
                GAS_LIMIT,
                nonce,
                null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());
        ClientRequest signedRequest = signRequest(requestId, signedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        submitReplayableRequest(requestId, signedRequest, "replayable native transfer");

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(10));

        assertEquals(1, clientContext.getCommitedLog().size(),
                "Duplicate replay must not create a second committed log entry");
    }

    @Test
    void staleNonceDoubleSpendIsRejectedAfterFirstCommit() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address firstReceiver = clientContext.getConfig().getProcessInfo("client2").getAddress();
        Address secondReceiver = SECOND_RECEIVER;
        DepChainWorldState initialState = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        BigInteger initialSecondReceiverBalance = initialState.getBalance(secondReceiver);

        long nonce = clientContext.getNonce();
        clientLibrary.submitNativeTransfer(firstReceiver.toHexString(), TRANSFER_VALUE, GAS_PRICE, GAS_LIMIT);
        waitForCommitLogSize(1, TimeUnit.SECONDS.toMillis(120));

        ClientRequest secondRequest = buildSignedRequest(201, sender, secondReceiver, nonce);
        submitReplayableRequest(201, secondRequest, "stale nonce double spend");

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(10));

        assertEquals(initialSecondReceiverBalance,
                ServerApp.getCoordinator("s0").getServerContext().getWorldState().getBalance(secondReceiver));
        assertEquals(1, clientContext.getCommitedLog().size(), "Only the first spend should commit");
    }

    @Test
    void sameSignedTransactionUnderDifferentRequestIdsDoesNotReplayTwice() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address receiver = clientContext.getConfig().getProcessInfo("client2").getAddress();

        Transaction unsignedTx = new Transaction(
                sender,
                receiver,
                TRANSFER_VALUE,
                new byte[0],
                GAS_PRICE,
                GAS_LIMIT,
                clientContext.getNonce(),
                null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());

        ClientRequest firstRequest = signRequest(300, signedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());
        submitReplayableRequest(300, firstRequest, "first signed tx request");
        waitForCommitLogSize(1, TimeUnit.SECONDS.toMillis(120));

        ClientRequest secondRequest = signRequest(301, signedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());
        submitReplayableRequest(301, secondRequest, "replayed signed tx under new request id");

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(10));

        assertEquals(1, clientContext.getCommitedLog().size(),
                "Same signed tx under a new request id must not commit twice");
    }

    private void submitReplayableRequest(int requestId, ClientRequest signedRequest, String requestDescription) {
        clientContext.registerRequestInMap(requestId, requestDescription);
        messageHandler.getPendingRequests().put(requestId, new ConcurrentHashMap<>());
        messageHandler.registerFuture(requestId);

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(signedRequest)
                .build();

        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, appMsg.toByteArray());
    }

    private ClientRequest buildSignedRequest(int requestId, Address sender, Address receiver, long nonce)
            throws Exception {
        Transaction unsignedTx = new Transaction(
                sender,
                receiver,
                TRANSFER_VALUE,
                new byte[0],
                GAS_PRICE,
                GAS_LIMIT,
                nonce,
                null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());
        return signRequest(requestId, signedTx, clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm());
    }

    private static ClientRequest signRequest(int requestId, Transaction signedTx, PrivateKey privateKey,
            String signatureAlgorithm) throws Exception {
        ClientRequest unsignedReq = ClientRequest.newBuilder()
                .setClientId("client1")
                .setRequestId(requestId)
                .setTransaction(signedTx.toProto())
                .build();

        byte[] sig = Crypto.sign(unsignedReq.toByteArray(), privateKey, signatureAlgorithm);
        return ClientRequest.newBuilder(unsignedReq)
                .setSignature(ByteString.copyFrom(sig))
                .build();
    }

    private void waitForCommitLogSize(int expectedSize, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (clientContext.getCommitedLog().size() >= expectedSize) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        fail("Timed out waiting for committed log size " + expectedSize + "; actual="
                + clientContext.getCommitedLog().size());
    }

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
}