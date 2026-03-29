package ist.depchain.common.utils;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.web3j.crypto.Hash;


/*
    Utility class for encoding ERC20 contract calls according to the Ethereum ABI (Application Binary Interface) specification
    This is used by the client to construct calldata for ERC20 operations, and by the replica to decode them.
*/
public final class Erc20Abi {

    private Erc20Abi() {}

    public static String smartContractMethodIdentifier(String signature) {
        String hashHex = Hash.sha3String(signature); // Keccak-256 hash function 
        return hashHex.substring(2, 10); // sha3String returns "0x" + 8 hex chars for the method ID + the rest of the hash, so we take chars 2..10
    }

    public static String encodeAddress(Address address) {
        String hex = address.toHexString().substring(2); // remove "0x" prefix
        return padTo32(hex); // left-pad to 32 bytes (64 hex chars) for ABI encoding
    }
    
    public static String encodeUint256(BigInteger value) {
        String hex = value.toString(16);
        if (hex.length() > 64) {
            throw new IllegalArgumentException("uint256 too large");
        }
        return padTo32(hex);
    }

    private static String padTo32(String hex) {
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        return "0".repeat(64 - hex.length()) + hex;
    }

    public static byte[] balanceOf(Address owner) {
        return Bytes.fromHexString("0x" + smartContractMethodIdentifier("balanceOf(address)")
                        + encodeAddress(owner)) // argument: owner address, left-padded to 32 bytes
                        .toArrayUnsafe();
    }

    public static byte[] transfer(Address to, BigInteger amount) {
        return Bytes.fromHexString("0x" + smartContractMethodIdentifier("transfer(address,uint256)") 
                        + encodeAddress(to) // argument 1: to address
                        + encodeUint256(amount) // argument 2: amount
                    ).toArrayUnsafe();
    }

    public static byte[] increaseAllowance(Address spender, BigInteger amount) {
        return Bytes.fromHexString("0x" + smartContractMethodIdentifier("increaseAllowance(address,uint256)")
                        + encodeAddress(spender) // argument 1: spender address
                        + encodeUint256(amount) // argument 2: amount
                    ).toArrayUnsafe();
    }

    public static byte[] approve(Address spender, BigInteger amount) {
        return Bytes.fromHexString("0x" + smartContractMethodIdentifier("approve(address,uint256)")
                        + encodeAddress(spender) // argument 1: spender address
                        + encodeUint256(amount) // argument 2: amount
                    ).toArrayUnsafe();
    }

    public static byte[] decreaseAllowance(Address spender, BigInteger amount) {
        return Bytes.fromHexString("0x" + smartContractMethodIdentifier("decreaseAllowance(address,uint256)")
                        + encodeAddress(spender) // argument 1: spender address
                        + encodeUint256(amount) // argument 2: amount
                    ).toArrayUnsafe();
    }

    public static byte[] allowance(Address owner, Address spender) {
        return Bytes.fromHexString("0x" + smartContractMethodIdentifier("allowance(address,address)")
                        + encodeAddress(owner) // argument 1: owner address
                        + encodeAddress(spender) // argument 2: spender address
                    ).toArrayUnsafe();
    }

    public static byte[] transferFrom(Address from, Address to, BigInteger amount) {
        return Bytes.fromHexString("0x" + smartContractMethodIdentifier("transferFrom(address,address,uint256)")
                        + encodeAddress(from) // argument 1: from address
                        + encodeAddress(to) // argument 2: to address
                        + encodeUint256(amount) // argument 3: amount   
                    ).toArrayUnsafe();
    }
}