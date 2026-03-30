package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import com.google.protobuf.ByteString;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ist.depchain.client.ClientContext;
import ist.depchain.common.Block;
import ist.depchain.common.ClientRequestMeta;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.utils.TransactionSigner;
import ist.depchain.core.ServerContext;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

/**
 * Byzantine client + Byzantine leader collusion tests (TODO-TESTS §J).
 *
 * Scenario: a Byzantine client crafts a malformed but plausible transaction,
 * and a colluding Byzantine leader includes it in a PREPARE proposal.
 * Honest replicas must still refuse to vote for the invalid block.
 *
 * Tests:
 *   1. Client signs a tx with a valid signature but from an unknown account
 *      (not in world state) — leader includes it — honest replicas reject.
 *   2. Client submits a tx but the leader tampers with the tx content after
 *      signing (invalidates the signature) — honest replicas reject.
 *   3. Client signs a tx but the leader crafts a block where tx count !=
 *      metadata count — honest replicas reject.
 */
class ByzantineClientLeaderCollusionTest {

    private static Config serverConfig;
    private static ServerContext serverContext;
    private static BasicHotStuffCoordinator coordinator;
    private static Config clientConfig;
    private static ClientContext clientContext;

    @BeforeAll
    static void setupSharedContext() throws Exception {
        serverConfig = Config.loadConfiguration("../config/config-dev.json", "s0");
        serverContext = new ServerContext(serverConfig);
        coordinator = new BasicHotStuffCoordinator(serverContext, false);

        clientConfig = Config.loadConfiguration("../config/config-dev.json", "client1");
        clientContext = new ClientContext(clientConfig);
    }

    @AfterAll
    static void teardownSharedContext() throws Exception {
        if (coordinator != null) coordinator.stop();
        if (serverContext != null) serverContext.stop();
        if (clientContext != null) clientContext.stop();
    }

    @Test
    void honestReplicaRejectsTransactionFromUnknownAccount() throws Exception {
        // Byzantine client: generates a fresh key pair not registered in the world state
        KeyPair byzantineClientKeys = genKeyPair();
        Address byzantineAddress = AddressUtils.deriveAddress(byzantineClientKeys.getPublic());
        Address receiver = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

        // The account does NOT exist in world state — no createEOA call
        assertFalse(serverContext.getWorldState().accountExists(byzantineAddress),
                "Byzantine address must not already exist in world state");

        // Create a validly-signed transaction from the unknown account
        Transaction unsignedTx = new Transaction(
                byzantineAddress, receiver, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, byzantineClientKeys.getPrivate(),
                serverConfig.getSignatureAlgorithm());

        // Byzantine leader includes it in a PREPARE block
        Block malformedBlock = Block.newBuilder()
                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                .setParentId(ByteString.EMPTY)
                .addTransactions(signedTx.toProto())
                .addRequestMeta(ClientRequestMeta.newBuilder().setClientId("byzantine").setRequestId(1).build())
                .build();

        HotStuffMessage prepare = HotStuffMessage.newBuilder()
                .setType(HotStuffMessage.Type.PREPARE)
                .setViewNumber(1)
                .setBlock(malformedBlock)
                .setJustify(QC.newBuilder()
                        .setType(HotStuffMessage.Type.DECIDE)
                        .setViewNumber(0)
                        .setBlockId(ByteString.EMPTY)
                        .build())
                .build();

        coordinator.onReceivePrepare("s1", prepare);

        assertNull(coordinator.getTree().getBlock(malformedBlock.getId()),
                "block with tx from unknown account must not be cached");
        assertTrue(coordinator.getExecutedBlockIds().isEmpty(),
                "block with tx from unknown account must not be executed");
    }

    @Test
    void honestReplicaRejectsLeaderTamperedTransactionContent() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

        DepChainWorldState ws = serverContext.getWorldState();
        if (!ws.accountExists(sender)) {
            ws.createEOA(sender, 0, BigInteger.valueOf(1_000_000));
        }
        if (!ws.accountExists(receiver)) {
            ws.createEOA(receiver, 0, BigInteger.ZERO);
        }

        // Client signs a legitimate tx for value=100
        Transaction legitimateTx = new Transaction(
                sender, receiver, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), ws.getNonce(sender), null);

        Transaction signedLegitimate = TransactionSigner.sign(legitimateTx, clientContext.getPrivateKey(),
                clientConfig.getSignatureAlgorithm());

        // Byzantine leader tampers: changes the value to 999_999 but keeps the original signature
        Transaction tamperedTx = new Transaction(
                sender, receiver, BigInteger.valueOf(999_999), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), ws.getNonce(sender),
                signedLegitimate.getSignature());

        Block malformedBlock = Block.newBuilder()
                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                .setParentId(ByteString.EMPTY)
                .addTransactions(tamperedTx.toProto())
                .addRequestMeta(ClientRequestMeta.newBuilder().setClientId("client1").setRequestId(1).build())
                .build();

        HotStuffMessage prepare = HotStuffMessage.newBuilder()
                .setType(HotStuffMessage.Type.PREPARE)
                .setViewNumber(1)
                .setBlock(malformedBlock)
                .setJustify(QC.newBuilder()
                        .setType(HotStuffMessage.Type.DECIDE)
                        .setViewNumber(0)
                        .setBlockId(ByteString.EMPTY)
                        .build())
                .build();

        coordinator.onReceivePrepare("s1", prepare);

        assertNull(coordinator.getTree().getBlock(malformedBlock.getId()),
                "block with tampered tx must not be cached");
        assertTrue(coordinator.getExecutedBlockIds().isEmpty(),
                "block with tampered tx must not be executed");
    }

    @Test
    void honestReplicaRejectsBlockWithExtraMetadataEntries() throws Exception {
        Address sender = clientContext.getSelfAddress();
        Address receiver = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

        DepChainWorldState ws = serverContext.getWorldState();
        if (!ws.accountExists(sender)) {
            ws.createEOA(sender, 0, BigInteger.valueOf(1_000_000));
        }
        if (!ws.accountExists(receiver)) {
            ws.createEOA(receiver, 0, BigInteger.ZERO);
        }

        Transaction legitimateTx = new Transaction(
                sender, receiver, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), ws.getNonce(sender), null);

        Transaction signedTx = TransactionSigner.sign(legitimateTx, clientContext.getPrivateKey(),
                clientConfig.getSignatureAlgorithm());

        // 1 transaction but 2 metadata entries — Byzantine leader inflates metadata
        Block malformedBlock = Block.newBuilder()
                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                .setParentId(ByteString.EMPTY)
                .addTransactions(signedTx.toProto())
                .addRequestMeta(ClientRequestMeta.newBuilder().setClientId("client1").setRequestId(1).build())
                .addRequestMeta(ClientRequestMeta.newBuilder().setClientId("client1").setRequestId(2).build())
                .build();

        HotStuffMessage prepare = HotStuffMessage.newBuilder()
                .setType(HotStuffMessage.Type.PREPARE)
                .setViewNumber(1)
                .setBlock(malformedBlock)
                .setJustify(QC.newBuilder()
                        .setType(HotStuffMessage.Type.DECIDE)
                        .setViewNumber(0)
                        .setBlockId(ByteString.EMPTY)
                        .build())
                .build();

        coordinator.onReceivePrepare("s1", prepare);

        assertNull(coordinator.getTree().getBlock(malformedBlock.getId()),
                "block with metadata mismatch must not be cached");
        assertTrue(coordinator.getExecutedBlockIds().isEmpty(),
                "block with metadata mismatch must not be executed");
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}
