package ist.depchain.common.utils;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.web3j.crypto.Hash;

public final class Erc20Abi {

    private Erc20Abi() {}

    public static String selector(String signature) {
        String hashHex = Hash.sha3String(signature);
        return hashHex.substring(2, 10);
    }

    public static String encodeAddress(Address address) {
        String hex = address.toHexString().substring(2);
        return "000000000000000000000000" + hex;
    }

    public static String encodeUint256(BigInteger value) {
        String hex = value.toString(16);
        if (hex.length() > 64) {
            throw new IllegalArgumentException("uint256 too large");
        }
        return "0".repeat(64 - hex.length()) + hex;
    }

    public static byte[] balanceOf(Address owner) {
        return Bytes.fromHexString(
                "0x" + selector("balanceOf(address)") + encodeAddress(owner)
        ).toArrayUnsafe();
    }

    public static byte[] transfer(Address to, BigInteger amount) {
        return Bytes.fromHexString(
                "0x" + selector("transfer(address,uint256)")
                        + encodeAddress(to)
                        + encodeUint256(amount)
        ).toArrayUnsafe();
    }

    public static byte[] increaseAllowance(Address spender, BigInteger amount) {
        return Bytes.fromHexString(
                "0x" + selector("increaseAllowance(address,uint256)")
                        + encodeAddress(spender)
                        + encodeUint256(amount)
        ).toArrayUnsafe();
    }

    public static byte[] decreaseAllowance(Address spender, BigInteger amount) {
        return Bytes.fromHexString(
                "0x" + selector("decreaseAllowance(address,uint256)")
                        + encodeAddress(spender)
                        + encodeUint256(amount)
        ).toArrayUnsafe();
    }

    public static byte[] allowance(Address owner, Address spender) {
        return Bytes.fromHexString(
                "0x" + selector("allowance(address,address)")
                        + encodeAddress(owner)
                        + encodeAddress(spender)
        ).toArrayUnsafe();
    }

    public static byte[] transferFrom(Address from, Address to, BigInteger amount) {
        return Bytes.fromHexString(
                "0x" + selector("transferFrom(address,address,uint256)")
                        + encodeAddress(from)
                        + encodeAddress(to)
                        + encodeUint256(amount)
        ).toArrayUnsafe();
    }
}