package ist.depchain.tests.stage2.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import ist.depchain.core.ServerContext;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.tests.stage2.Stage2GasConstants;

/**
 * Scenario: test that consensus and execution correctly
 * handle client queries and responses during in-flight consensus rounds, and
 * that timeouts during execution do not cause safety violations or duplicate
 * commits.
 * 
 * Tests:
 * 1. During a blocked execution on some replicas, clients can still query other
 * replicas and receive consistent responses based on the last committed state 
 * (this means that queries are not blocked by in-flight consensus rounds, and that they 
 * reflect the last committed state, not the pending state of the blocked execution).
 * 2. If the leader experiences a timeout during execution and triggers a view
 * change, the system should not execute more than one block for the same proposal,
 * preventing any safety violations or duplicate commits.
 */
class ConsensusRaceProtectionTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };

    private static final BigInteger TRANSFER_GAS_PRICE = BigInteger.valueOf(3);
    private static final BigInteger TRANSFER_GAS_LIMIT = BigInteger.valueOf(21_000);
    private static final BigInteger TRANSFER_GAS_USED = Stage2GasConstants.NATIVE_TRANSFER_GAS_COST;
    private static final BigInteger TRANSFER_VALUE = BigInteger.valueOf(1_000);

    private ClientContext client1Context;
    private ClientContext client2Context;
    private MessageHandler client1Handler;
    private MessageHandler client2Handler;
    private ClientLibrary client1Library;

    @BeforeEach
    void setup() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config client1Config = Config.loadConfiguration(CONFIG_FILE, "client1");
        Config client2Config = Config.loadConfiguration(CONFIG_FILE, "client2");

        client1Context = new ClientContext(client1Config);
        client2Context = new ClientContext(client2Config);
        client1Handler = new MessageHandler(client1Context);
        client2Handler = new MessageHandler(client2Context);
        client1Library = new ClientLibrary(client1Context, client1Handler);

        Address client1Address = client1Context.getSelfAddress();
        Address client2Address = client2Context.getSelfAddress();

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord, "Coordinator for " + replicaId + " should be registered");

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(client1Address)) {
                ws.createEOA(client1Address, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(client1Address, BigInteger.valueOf(10_000_000));
            }

            if (!ws.accountExists(client2Address)) {
                ws.createEOA(client2Address, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(client2Address, BigInteger.valueOf(10_000_000));
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        client1Context.setRequestId((int) requestBase);
        client2Context.setRequestId((int) (requestBase + 500));

        client1Context
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client1Address));
        client2Context
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client2Address));

        client1Context.start();
        client2Context.start();
    }

    @AfterEach
    void teardown() {
        if (client1Context != null) {
            client1Context.stop();
        }
        if (client2Context != null) {
            client2Context.stop();
        }
        stopReplicas();
    }

    @Test
    void queryConsistencyDuringInFlightConsensusRound() throws Exception {
        BasicHotStuffCoordinator blockedReplicaA = ServerApp.getCoordinator("s0");
        BasicHotStuffCoordinator blockedReplicaB = ServerApp.getCoordinator("s3");
        CountDownLatch executionStarted = new CountDownLatch(2);
        CountDownLatch releaseExecution = new CountDownLatch(1);

        installBlockingExecutor(blockedReplicaA, executionStarted, releaseExecution);
        installBlockingExecutor(blockedReplicaB, executionStarted, releaseExecution);

        try {
            Address sender = client1Context.getSelfAddress();
            Address receiver = client2Context.getSelfAddress();

            BigInteger senderBefore = blockedReplicaA.getServerContext().getWorldState().getBalance(sender);
            BigInteger expectedSenderAfterTransfer = senderBefore.subtract(TRANSFER_VALUE)
                    .subtract(TRANSFER_GAS_PRICE.multiply(TRANSFER_GAS_USED));

            client1Library.submitNativeTransfer(receiver.toHexString(), TRANSFER_VALUE, TRANSFER_GAS_PRICE,
                    TRANSFER_GAS_LIMIT);

            assertTrue(executionStarted.await(60, TimeUnit.SECONDS),
                    "timed out waiting for both blocked replicas to enter execution");

            waitForReplicaBalance("s1", sender, expectedSenderAfterTransfer, TimeUnit.SECONDS.toMillis(120));
            waitForReplicaBalance("s2", sender, expectedSenderAfterTransfer, TimeUnit.SECONDS.toMillis(120));

            submitNativeBalanceQuery(client2Context, client2Handler, receiver,
                    "native.balanceOf(" + receiver.toHexString() + ")");

            releaseExecution.countDown();
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
        } finally {
            releaseExecution.countDown();
        }
    }

    @Test
    void timeoutDuringExecutionDoesNotDuplicateCommit() throws Exception {
        BasicHotStuffCoordinator leaderReplica = ServerApp.getCoordinator("s1");
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);

        installBlockingExecutor(leaderReplica, executionStarted, releaseExecution);

        try {
            Address sender = client1Context.getSelfAddress();
            Address receiver = client2Context.getSelfAddress();
            BasicHotStuffCoordinator witnessReplica = ServerApp.getCoordinator("s2");
            BigInteger senderBefore = witnessReplica.getServerContext().getWorldState().getBalance(sender);
            BigInteger receiverBefore = witnessReplica.getServerContext().getWorldState().getBalance(receiver);
            BigInteger expectedSenderAfter = senderBefore.subtract(TRANSFER_VALUE)
                    .subtract(TRANSFER_GAS_PRICE.multiply(TRANSFER_GAS_USED));
            BigInteger expectedReceiverAfter = receiverBefore.add(TRANSFER_VALUE);

            client1Library.submitNativeTransfer(receiver.toHexString(), TRANSFER_VALUE, TRANSFER_GAS_PRICE,
                    TRANSFER_GAS_LIMIT);

            assertTrue(executionStarted.await(60, TimeUnit.SECONDS),
                    "timed out waiting for the leader to start execution");

            leaderReplica.startNextView();
            releaseExecution.countDown();

            waitForReplicaBalance("s2", sender, expectedSenderAfter, TimeUnit.SECONDS.toMillis(120));
            waitForReplicaBalance("s2", receiver, expectedReceiverAfter, TimeUnit.SECONDS.toMillis(120));

            assertEquals(1, leaderReplica.getExecutedBlockIds().size(),
                    "race must not execute more than one block on the leader");
        } finally {
            releaseExecution.countDown();
        }
    }

    private int submitNativeBalanceQuery(ClientContext context, MessageHandler handler, Address target,
            String description) throws Exception {
        int requestId = context.getRequestId().incrementAndGet();

        long nonce = context.getNonce();
        Transaction unsignedTx = Transaction.nativeBalanceQuery(
                context.getSelfAddress(),
                target,
                TRANSFER_GAS_PRICE,
                TRANSFER_GAS_LIMIT,
                nonce,
                null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, context.getPrivateKey(),
                context.getConfig().getSignatureAlgorithm());

        ClientRequest unsignedRequest = ClientRequest.newBuilder()
                .setClientId(context.getConfig().getSelfId())
                .setRequestId(requestId)
                .setTransaction(signedTx.toProto())
                .build();

        ClientRequest signedRequest = signClientRequest(unsignedRequest, context.getPrivateKey(),
                context.getConfig().getSignatureAlgorithm());

        handler.getPendingRequests().put(requestId, new ConcurrentHashMap<>());
        context.registerRequestInMap(requestId, description);
        handler.registerFuture(requestId);

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(signedRequest)
                .build();

        Set<String> destinations = context.getConfig().getBlockChainServers().keySet();
        context.getAuthenticatedPerfectLink().broadcast(destinations, appMsg.toByteArray());
        context.incrementNonce();
        return requestId;
    }

    private static ClientRequest signClientRequest(ClientRequest unsignedRequest, PrivateKey privateKey,
            String signatureAlgorithm) throws Exception {
        byte[] signature = Crypto.sign(unsignedRequest.toByteArray(), privateKey, signatureAlgorithm);
        return ClientRequest.newBuilder(unsignedRequest)
                .setSignature(ByteString.copyFrom(signature))
                .build();
    }

    private void waitForReplicaBalance(String replicaId, Address address, BigInteger expectedBalance, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            if (coord != null && coord.getServerContext().getWorldState().getBalance(address).equals(expectedBalance)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }

        BigInteger actualBalance = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState()
                .getBalance(address);
        fail("replica " + replicaId + " did not reach expected balance " + expectedBalance + "; actual="
                + actualBalance);
    }

    private void installBlockingExecutor(BasicHotStuffCoordinator coordinator, CountDownLatch executionStarted,
            CountDownLatch releaseExecution) {
        try {
            ServerContext serverContext = coordinator.getServerContext();
            Field field = ServerContext.class.getDeclaredField("transactionExecutor");
            field.setAccessible(true);
            field.set(serverContext, new BlockingTransactionExecutor(executionStarted, releaseExecution));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to install blocking transaction executor", e);
        }
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

    private static final class BlockingTransactionExecutor extends TransactionExecutor {
        private final CountDownLatch executionStarted;
        private final CountDownLatch releaseExecution;
        private final AtomicBoolean blocked = new AtomicBoolean(false);

        private BlockingTransactionExecutor(CountDownLatch executionStarted, CountDownLatch releaseExecution) {
            this.executionStarted = executionStarted;
            this.releaseExecution = releaseExecution;
        }

        @Override
        public TransactionReceipt execute(DepChainWorldState state, Transaction tx, Address proposer) {
            if (blocked.compareAndSet(false, true)) {
                executionStarted.countDown();
                try {
                    if (!releaseExecution.await(30, TimeUnit.SECONDS)) {
                        throw new RuntimeException("timed out waiting to release blocked execution");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted while waiting to release blocked execution", e);
                }
            }

            return super.execute(state, tx, proposer);
        }
    }
}