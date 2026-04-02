package ist.depchain.tests.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import ist.depchain.common.utils.Erc20Abi;

/**
 * Unit tests for Erc20Abi, which provides utility methods for encoding ERC-20
 * smart contract method calls and parameters into the correct ABI format for
 * inclusion in transaction calldata.
 */
public class Erc20AbiTest {

    private static final Address A = Address.fromHexString("0x1111111111111111111111111111111111111111");
    private static final Address B = Address.fromHexString("0x2222222222222222222222222222222222222222");

    @Test
    void smartContractMethodIdentifierProducesExpectedLength() {
        assertEquals(8, Erc20Abi.smartContractMethodIdentifier("transfer(address,uint256)").length());
        assertEquals(8, Erc20Abi.smartContractMethodIdentifier("balanceOf(address)").length());
        assertEquals(8, Erc20Abi.smartContractMethodIdentifier("allowance(address,address)").length());
    }

    @Test
    void encodeAddressIs32BytesLeftPadded() {
        String encoded = Erc20Abi.encodeAddress(A);

        assertEquals(64, encoded.length());
        assertTrue(encoded.startsWith("000000000000000000000000"));
        assertTrue(encoded.endsWith("1111111111111111111111111111111111111111"));
    }

    @Test
    void encodeUint256Is32BytesLeftPadded() {
        String encoded = Erc20Abi.encodeUint256(BigInteger.valueOf(255));

        assertEquals(64, encoded.length());
        assertTrue(encoded.endsWith("ff"));
        assertEquals(
                "00000000000000000000000000000000000000000000000000000000000000ff",
                encoded);
    }

    @Test
    void transferCalldataHasCorrectShape() {
        byte[] calldata = Erc20Abi.transfer(B, BigInteger.valueOf(5000));
        String hex = bytesToHex(calldata);

        assertEquals(8 + 64 + 64, hex.length());

        String expectedsmartContractMethodIdentifier = Erc20Abi
                .smartContractMethodIdentifier("transfer(address,uint256)");
        assertTrue(hex.startsWith(expectedsmartContractMethodIdentifier));

        String encodedAddress = Erc20Abi.encodeAddress(B);
        String encodedAmount = Erc20Abi.encodeUint256(BigInteger.valueOf(5000));

        assertEquals(expectedsmartContractMethodIdentifier + encodedAddress + encodedAmount, hex);
    }

    @Test
    void increaseAllowanceCalldataHasCorrectShape() {
        byte[] calldata = Erc20Abi.increaseAllowance(B, BigInteger.valueOf(123));
        String hex = bytesToHex(calldata);

        assertEquals(8 + 64 + 64, hex.length());

        String expected = Erc20Abi.smartContractMethodIdentifier("increaseAllowance(address,uint256)")
                + Erc20Abi.encodeAddress(B)
                + Erc20Abi.encodeUint256(BigInteger.valueOf(123));

        assertEquals(expected, hex);
    }

    @Test
    void decreaseAllowanceCalldataHasCorrectShape() {
        byte[] calldata = Erc20Abi.decreaseAllowance(B, BigInteger.valueOf(77));
        String hex = bytesToHex(calldata);

        String expected = Erc20Abi.smartContractMethodIdentifier("decreaseAllowance(address,uint256)")
                + Erc20Abi.encodeAddress(B)
                + Erc20Abi.encodeUint256(BigInteger.valueOf(77));

        assertEquals(expected, hex);
    }

    @Test
    void allowanceCalldataHasCorrectShape() {
        byte[] calldata = Erc20Abi.allowance(A, B);
        String hex = bytesToHex(calldata);

        assertEquals(8 + 64 + 64, hex.length());

        String expected = Erc20Abi.smartContractMethodIdentifier("allowance(address,address)")
                + Erc20Abi.encodeAddress(A)
                + Erc20Abi.encodeAddress(B);

        assertEquals(expected, hex);
    }

    @Test
    void transferFromCalldataHasCorrectShape() {
        byte[] calldata = Erc20Abi.transferFrom(A, B, BigInteger.valueOf(250));
        String hex = bytesToHex(calldata);

        assertEquals(8 + 64 + 64 + 64, hex.length());

        String expected = Erc20Abi.smartContractMethodIdentifier("transferFrom(address,address,uint256)")
                + Erc20Abi.encodeAddress(A)
                + Erc20Abi.encodeAddress(B)
                + Erc20Abi.encodeUint256(BigInteger.valueOf(250));

        assertEquals(expected, hex);
    }

    @Test
    void balanceOfCalldataHasCorrectShape() {
        byte[] calldata = Erc20Abi.balanceOf(A);
        String hex = bytesToHex(calldata);

        assertEquals(8 + 64, hex.length());

        String expected = Erc20Abi.smartContractMethodIdentifier("balanceOf(address)")
                + Erc20Abi.encodeAddress(A);

        assertEquals(expected, hex);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}