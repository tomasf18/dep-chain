package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import com.google.protobuf.ByteString;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import ist.depchain.common.Block;
import ist.depchain.common.ClientRequestMeta;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.utils.TransactionSigner;
import ist.depchain.core.ServerContext;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.client.ClientContext;

class ByzantineLeaderMalformedBlockStage2Test {

    @Test
    void honestReplicaRejectsForgedTransactionInPrepare() throws Exception {
        Config config = Config.loadConfiguration("../config/config-dev.json", "s0");
        ServerContext serverContext = new ServerContext(config);
        BasicHotStuffCoordinator coordinator = new BasicHotStuffCoordinator(serverContext, false);

        KeyPair attackerKeys = genKeyPair();
        Address owner = config.getInitialTokenHolderAddress();
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        DepChainWorldState ws = serverContext.getWorldState();

        Transaction forgedTx = new Transaction(
                owner,
                receiver,
                BigInteger.valueOf(100),
                new byte[0],
                BigInteger.ONE,
                BigInteger.valueOf(21_000),
                ws.getNonce(owner),
                null
        ).withSignature(Crypto.sign(
                new Transaction(
                        owner,
                        receiver,
                        BigInteger.valueOf(100),
                        new byte[0],
                        BigInteger.ONE,
                        BigInteger.valueOf(21_000),
                        ws.getNonce(owner),
                        null
                ).toUnsignedBytes(),
                attackerKeys.getPrivate(),
                config.getSignatureAlgorithm()));

        Block malformedBlock = Block.newBuilder()
                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                .setParentId(ByteString.EMPTY)
                .addTransactions(forgedTx.toProto())
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

        assertNull(coordinator.getTree().getBlock(malformedBlock.getId()), "invalid prepared block must not be cached");
        assertTrue(coordinator.getExecutedBlockIds().isEmpty(), "invalid prepared block must not be executed");

        coordinator.stop();
        serverContext.stop();
    }

    @Test
    void honestReplicaRejectsUnsignedTransactionInjectedByLeader() throws Exception {
        Config config = Config.loadConfiguration("../config/config-dev.json", "s0");
        ServerContext serverContext = new ServerContext(config);
        BasicHotStuffCoordinator coordinator = new BasicHotStuffCoordinator(serverContext, false);

        Address owner = config.getInitialTokenHolderAddress();
        Address receiver = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        DepChainWorldState ws = serverContext.getWorldState();

        Transaction unsignedTx = new Transaction(
                owner,
                receiver,
                BigInteger.valueOf(100),
                new byte[0],
                BigInteger.ONE,
                BigInteger.valueOf(21_000),
                ws.getNonce(owner),
                null
        );

        Block malformedBlock = Block.newBuilder()
                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                .setParentId(ByteString.EMPTY)
                .addTransactions(unsignedTx.toProto())
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

        assertNull(coordinator.getTree().getBlock(malformedBlock.getId()), "unsigned prepared block must not be cached");
        assertTrue(coordinator.getExecutedBlockIds().isEmpty(), "unsigned prepared block must not be executed");

        coordinator.stop();
        serverContext.stop();
    }

        @Test
        void honestReplicaRejectsDuplicateNonceTransactionsInSameBlock() throws Exception {
                Config clientConfig = Config.loadConfiguration("../config/config-dev.json", "client1");
                ClientContext clientContext = new ClientContext(clientConfig);

                Config config = Config.loadConfiguration("../config/config-dev.json", "s0");
                ServerContext serverContext = new ServerContext(config);
                BasicHotStuffCoordinator coordinator = new BasicHotStuffCoordinator(serverContext, false);

                Address owner = clientContext.getSelfAddress();
                Address receiverA = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
                Address receiverB = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

                DepChainWorldState ws = serverContext.getWorldState();
                if (!ws.accountExists(owner)) {
                        ws.createEOA(owner, 0, BigInteger.valueOf(1_000_000));
                }
                if (!ws.accountExists(receiverA)) {
                        ws.createEOA(receiverA, 0, BigInteger.ZERO);
                }
                if (!ws.accountExists(receiverB)) {
                        ws.createEOA(receiverB, 0, BigInteger.ZERO);
                }

                long nonce = ws.getNonce(owner);
                Transaction tx1 = TransactionSigner.sign(
                                new Transaction(owner, receiverA, BigInteger.valueOf(100), new byte[0], BigInteger.ONE, BigInteger.valueOf(21_000), nonce, null),
                                clientContext.getPrivateKey(),
                                clientConfig.getSignatureAlgorithm());
                Transaction tx2 = TransactionSigner.sign(
                                new Transaction(owner, receiverB, BigInteger.valueOf(200), new byte[0], BigInteger.ONE, BigInteger.valueOf(21_000), nonce, null),
                                clientContext.getPrivateKey(),
                                clientConfig.getSignatureAlgorithm());

                Block duplicateNonceBlock = Block.newBuilder()
                                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                                .setParentId(ByteString.EMPTY)
                                .addTransactions(tx1.toProto())
                                .addTransactions(tx2.toProto())
                                .addRequestMeta(ClientRequestMeta.newBuilder().setClientId("client1").setRequestId(10).build())
                                .addRequestMeta(ClientRequestMeta.newBuilder().setClientId("client1").setRequestId(11).build())
                                .build();

                HotStuffMessage prepare = HotStuffMessage.newBuilder()
                                .setType(HotStuffMessage.Type.PREPARE)
                                .setViewNumber(1)
                                .setBlock(duplicateNonceBlock)
                                .setJustify(QC.newBuilder()
                                                .setType(HotStuffMessage.Type.DECIDE)
                                                .setViewNumber(0)
                                                .setBlockId(ByteString.EMPTY)
                                                .build())
                                .build();

                coordinator.onReceivePrepare("s1", prepare);

                assertNull(coordinator.getTree().getBlock(duplicateNonceBlock.getId()), "duplicate nonce block must not be cached");
                assertTrue(coordinator.getExecutedBlockIds().isEmpty(), "duplicate nonce block must not be executed");

                coordinator.stop();
                serverContext.stop();
                clientContext.stop();
        }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}