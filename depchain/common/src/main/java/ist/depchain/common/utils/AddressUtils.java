package ist.depchain.common.utils;

import java.math.BigInteger;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.crypto.Hash;
import org.apache.tuweni.bytes.Bytes;


/*
    This gives a deterministic account address derived from the client public key, 
    which is required for sender authorization.
*/
public final class AddressUtils {

    private AddressUtils() {}

    /**
     * Derive an Ethereum-style address from a Java ECPublicKey:
     * keccak256(uncompressed_pubkey_without_prefix)[12..31]
     */
    public static Address deriveAddress(PublicKey publicKey) {
        if (!(publicKey instanceof ECPublicKey ecPublicKey)) {
            throw new IllegalArgumentException("Expected ECPublicKey, got: " + publicKey.getClass().getName());
        }

        byte[] uncompressed = toUncompressedXY(ecPublicKey);
        byte[] hash = Hash.sha3(uncompressed);
        byte[] addressBytes = Arrays.copyOfRange(hash, 12, 32);

        return Address.wrap(Bytes.wrap(addressBytes));
    }

    private static byte[] toUncompressedXY(ECPublicKey publicKey) {
        BigInteger x = publicKey.getW().getAffineX();
        BigInteger y = publicKey.getW().getAffineY();

        byte[] xBytes = toFixedLength(x, 32);
        byte[] yBytes = toFixedLength(y, 32);

        byte[] out = new byte[64];
        System.arraycopy(xBytes, 0, out, 0, 32);
        System.arraycopy(yBytes, 0, out, 32, 32);
        return out;
    }

    private static byte[] toFixedLength(BigInteger value, int size) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == size) {
            return bytes;
        }
        if (bytes.length == size + 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        if (bytes.length < size) {
            byte[] padded = new byte[size];
            System.arraycopy(bytes, 0, padded, size - bytes.length, bytes.length);
            return padded;
        }
        throw new IllegalArgumentException("Value too large for " + size + " bytes");
    }
}