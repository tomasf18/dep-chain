package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.UUID;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import com.google.protobuf.ByteString;

import ist.depchain.common.Transaction;
import ist.depchain.common.Block;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Crypto;

import ist.depchain.core.BlockChain;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.BlockValidator;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.common.utils.Config;
import ist.depchain.common.ClientRequestMeta;
import ist.depchain.core.blockchain.ValidationResult;

class BlockValidationAndExecutionTest {

    @Test
    void executorDoesNotMutateCommittedStateWhenUsingSnapshot() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState committed = new DepChainWorldState(null);
        committed.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null);

        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction tx = unsignedTx.withSignature(sig);

        DepChainWorldState working = committed.copy();
        TransactionExecutor executor = new TransactionExecutor();
        TransactionReceipt receipt = executor.execute(working, tx, null);

        assertTrue(receipt.isSuccess());
        assertEquals(BigInteger.valueOf(1_000_000), committed.getBalance(from));
        assertEquals(0, committed.getNonce(from));
        assertNotEquals(committed.computeStateHash(), working.computeStateHash());
    }

    @Test
    void blockChainRejectsBrokenParentLink() {
        BlockChain chain = new BlockChain();
        BlockChainBlock genesis = new BlockChainBlock("h0", null, List.of(), 0);
        chain.addBlock(genesis);

        BlockChainBlock bad = new BlockChainBlock("h2", "WRONG_PARENT", List.of(), 1);

        assertThrows(IllegalStateException.class, () -> chain.addBlock(bad));
    }

    @Test
    void stateHashChangesWhenBalancesChange() {
        DepChainWorldState ws = new DepChainWorldState(null);
        Address a = Address.fromHexString("0x1111111111111111111111111111111111111111");

        ws.createEOA(a, 0, BigInteger.TEN);
        String h1 = ws.computeStateHash();

        ws.addBalance(a, BigInteger.ONE);
        String h2 = ws.computeStateHash();

        assertNotEquals(h1, h2);
    }

    @Test
    void blockValidatorRejectsForgedTransactionSignature() throws Exception {
        Config config = Config.loadConfiguration("../config/config-dev.json", "s0");

        KeyPair ownerKeys = genKeyPair();
        KeyPair forgedKeys = genKeyPair();
        Address owner = AddressUtils.deriveAddress(ownerKeys.getPublic());
        Address receiver = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(config);
        ws.createEOA(owner, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                owner, receiver, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null
        );

        byte[] forgedSig = Crypto.sign(unsignedTx.toUnsignedBytes(), forgedKeys.getPrivate(), "SHA256withECDSA");
        Transaction forgedTx = unsignedTx.withSignature(forgedSig);

        Block block = Block.newBuilder()
                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                .setParentId(ByteString.EMPTY)
            .addTransactions(forgedTx.toProto())
                .addRequestMeta(ClientRequestMeta.newBuilder().setClientId("client1").setRequestId(1).build())
                .build();

        ValidationResult result = BlockValidator.validateProposedBlock(block, ws, config);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("invalid tx"));
    }

    @Test
    void blockValidatorRejectsTransactionMetadataMismatch() {
        Config config = Config.loadConfiguration("../config/config-dev.json", "s0");
        DepChainWorldState ws = new DepChainWorldState(config);

        Transaction tx = new Transaction(
            Address.fromHexString("0x2222222222222222222222222222222222222222"),
            Address.fromHexString("0x1111111111111111111111111111111111111111"),
            BigInteger.ONE,
            new byte[0],
            BigInteger.ONE,
            BigInteger.valueOf(21_000),
            0,
            null
        );

        Block block = Block.newBuilder()
            .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
            .setParentId(ByteString.EMPTY)
            .addTransactions(tx.toProto())
            .build();

        ValidationResult result = BlockValidator.validateProposedBlock(block, ws, config);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("mismatch"));
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}