package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import ist.depchain.common.utils.AddressUtils;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.Transaction;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionValidator;

class TransactionValidationTest {

    @Test
    void acceptsValidTransaction() throws Exception {
        KeyPair kp = genKeyPair();
        PublicKey pub = kp.getPublic();
        Address from = AddressUtils.deriveAddress(pub);
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null
        );

        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        var result = TransactionValidator.validate(signed, pub, "SHA256withECDSA", ws, null);
        assertTrue(result.isValid(), result.getErrorMessage());
    }

    @Test
    void rejectsWrongNonce() throws Exception {
        KeyPair kp = genKeyPair();
        PublicKey pub = kp.getPublic();
        Address from = AddressUtils.deriveAddress(pub);
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 2, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null
        );

        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        var result = TransactionValidator.validate(signed, pub, "SHA256withECDSA", ws, null);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("nonce"));
    }

    @Test
    void acceptsInsufficientBalanceAtValidationTime() throws Exception {
        KeyPair kp = genKeyPair();
        PublicKey pub = kp.getPublic();
        Address from = AddressUtils.deriveAddress(pub);
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(10));

        Transaction unsignedTx = new Transaction(
                from, to, BigInteger.valueOf(5), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null
        );

        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        var result = TransactionValidator.validate(signed, pub, "SHA256withECDSA", ws, null);
        assertTrue(result.isValid(), result.getErrorMessage());
    }

    @Test
    void rejectsSignerSenderMismatch() throws Exception {
        KeyPair kp1 = genKeyPair();
        KeyPair kp2 = genKeyPair();

        PublicKey pub1 = kp1.getPublic();
        Address from1 = AddressUtils.deriveAddress(pub1);
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from1, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                from1, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null
        );

        byte[] wrongSig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp2.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(wrongSig);

        var result = TransactionValidator.validate(signed, pub1, "SHA256withECDSA", ws, null);
        assertFalse(result.isValid());
    }

    @Test
    void rejectsZeroGasPrice() throws Exception {
        KeyPair kp = genKeyPair();
        PublicKey pub = kp.getPublic();
        Address from = AddressUtils.deriveAddress(pub);
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ZERO, BigInteger.valueOf(21_000), 0, null
        );

        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        var result = TransactionValidator.validate(signed, pub, "SHA256withECDSA", ws, null);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("gasPrice"));
    }

    @Test
    void rejectsZeroGasLimit() throws Exception {
        KeyPair kp = genKeyPair();
        PublicKey pub = kp.getPublic();
        Address from = AddressUtils.deriveAddress(pub);
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                from, to, BigInteger.valueOf(100), new byte[0],
                BigInteger.ONE, BigInteger.ZERO, 0, null
        );

        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        var result = TransactionValidator.validate(signed, pub, "SHA256withECDSA", ws, null);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("gasLimit"));
    }

    @Test
    void rejectsNegativeValue() throws Exception {
        KeyPair kp = genKeyPair();
        PublicKey pub = kp.getPublic();
        Address from = AddressUtils.deriveAddress(pub);
        Address to = Address.fromHexString("0x1111111111111111111111111111111111111111");

        DepChainWorldState ws = new DepChainWorldState(null);
        ws.createEOA(from, 0, BigInteger.valueOf(1_000_000));

        Transaction unsignedTx = new Transaction(
                from, to, BigInteger.valueOf(-1), new byte[0],
                BigInteger.ONE, BigInteger.valueOf(21_000), 0, null
        );

        byte[] sig = Crypto.sign(unsignedTx.toUnsignedBytes(), kp.getPrivate(), "SHA256withECDSA");
        Transaction signed = unsignedTx.withSignature(sig);

        var result = TransactionValidator.validate(signed, pub, "SHA256withECDSA", ws, null);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("value"));
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }
}