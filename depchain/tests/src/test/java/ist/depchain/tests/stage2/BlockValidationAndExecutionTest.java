package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import ist.depchain.common.Transaction;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Crypto;

import ist.depchain.core.BlockChain;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.blockchain.TransactionExecutor;

class BlockValidationAndExecutionTest {

    @Test
    void executorDoesNotMutateCommittedStateWhenUsingSnapshot() throws Exception {
        KeyPair kp = genKeyPair();
        Address from = AddressUtils.deriveAddress(kp.getPublic());
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState committed = new DepChainWorldState();
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
        DepChainWorldState ws = new DepChainWorldState();
        Address a = Address.fromHexString("0x1111111111111111111111111111111111111111");

        ws.createEOA(a, 0, BigInteger.TEN);
        String h1 = ws.computeStateHash();

        ws.addBalance(a, BigInteger.ONE);
        String h2 = ws.computeStateHash();

        assertNotEquals(h1, h2);
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}