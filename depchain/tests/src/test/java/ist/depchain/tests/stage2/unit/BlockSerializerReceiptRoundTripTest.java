package ist.depchain.tests.stage2.unit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

import ist.depchain.common.Transaction;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.BlockSerializer;
import ist.depchain.core.blockchain.TransactionReceipt;

/**
 * Unit tests for: Block and TransactionReceipt JSON serialization and
 * deserialization, ensuring that all fields are preserved correctly, especially
 * return data and contract address in receipts.
 */
public class BlockSerializerReceiptRoundTripTest {

	@Test
	void serializerPreservesReturnDataAndContractAddress() {
		Address from = Address.fromHexString("0x1111111111111111111111111111111111111111");
		Address to = Address.fromHexString("0x2222222222222222222222222222222222222222");
		Address contract = Address.fromHexString("0x9999999999999999999999999999999999999999");

		Transaction tx = new Transaction(
				from,
				to,
				BigInteger.ZERO,
				new byte[] { 0x01, 0x02 },
				BigInteger.ONE,
				BigInteger.valueOf(100000),
				0,
				null);

		TransactionReceipt receipt = new TransactionReceipt(
				tx.txHash(),
				true,
				BigInteger.valueOf(90000),
				BigInteger.valueOf(90000),
				null,
				new byte[] { (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef },
				contract);

		BlockChainBlock block = new BlockChainBlock(
				"abc123",
				null,
				List.of(tx),
				List.of(receipt),
				0,
				null,
				"statehash");

		String json = BlockSerializer.toJson(block);
		BlockChainBlock parsed = BlockSerializer.fromJson(json);

		assertEquals(1, parsed.getReceipts().size());
		TransactionReceipt parsedReceipt = parsed.getReceipts().get(0);

		assertArrayEquals(receipt.getTxHash(), parsedReceipt.getTxHash());
		assertEquals(receipt.isSuccess(), parsedReceipt.isSuccess());
		assertEquals(receipt.getGasUsed(), parsedReceipt.getGasUsed());
		assertEquals(receipt.getFee(), parsedReceipt.getFee());
		assertArrayEquals(receipt.getReturnData(), parsedReceipt.getReturnData());
		assertEquals(receipt.getContractAddress(), parsedReceipt.getContractAddress());
	}
}