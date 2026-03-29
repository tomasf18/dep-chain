package ist.depchain.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.utils.Numeric;

import ist.depchain.common.Transaction;

/**
 * JSON serialization and deserialization for BlockChainBlock objects.
 * Format matches the genesis block format from the spec.
 */
public class BlockSerializer {

    private BlockSerializer() {
    }

    // --- Serialize ---

    public static String toJson(BlockChainBlock block) {
        return toJsonObject(block).toString();
    }

    public static JsonObject toJsonObject(BlockChainBlock block) {
        JsonObject root = new JsonObject();
        root.addProperty("block_hash", block.getBlockHash());

        if (block.getPreviousBlockHash() != null) {
            root.addProperty("previous_block_hash", block.getPreviousBlockHash());
        } else {
            root.add("previous_block_hash", JsonNull.INSTANCE);
        }

        root.addProperty("block_number", block.getBlockNumber());

        if (block.getProposer() != null) {
            root.addProperty("proposer", block.getProposer().toHexString());
        } else {
            root.add("proposer", JsonNull.INSTANCE);
        }
        
        // Transactions
        JsonArray txArray = new JsonArray();
        for (Transaction tx : block.getTransactions()) {
            txArray.add(serializeTransaction(tx));
        }
        root.add("transactions", txArray);
        
        // Receipts
        if (!block.getReceipts().isEmpty()) {
            JsonArray receiptArray = new JsonArray();
            for (TransactionReceipt receipt : block.getReceipts()) {
                receiptArray.add(serializeReceipt(receipt));
            }
            root.add("receipts", receiptArray);
        }
        
        if (block.getStateHash() != null) {
            root.addProperty("state_hash", block.getStateHash());
        } else {
            root.add("state_hash", JsonNull.INSTANCE);
        }
        
        return root;
    }

    private static JsonObject serializeTransaction(Transaction tx) {
        JsonObject obj = new JsonObject();
        obj.addProperty("from", tx.getFrom().toHexString());

        if (tx.getTo() != null) {
            obj.addProperty("to", tx.getTo().toHexString());
        } else {
            obj.add("to", JsonNull.INSTANCE);
        }

        obj.addProperty("value", tx.getValue().toString());
        obj.addProperty("input", "0x" + Numeric.toHexStringNoPrefix(tx.getData()));
        obj.addProperty("gas_price", tx.getGasPrice().toString());
        obj.addProperty("gas_limit", tx.getGasLimit().toString());
        obj.addProperty("nonce", tx.getNonce());
        obj.addProperty("kind", tx.isNativeBalanceQuery() ? "NATIVE_BALANCE_QUERY" : "STANDARD");

        if (tx.isNativeBalanceQuery() && tx.getNativeBalanceQueryTarget() != null) {
            obj.addProperty("native_balance_query_target", tx.getNativeBalanceQueryTarget().toHexString());
        }

        if (tx.getSignature() != null) {
            obj.addProperty("signature", "0x" + Numeric.toHexStringNoPrefix(tx.getSignature()));
        }

        obj.addProperty("tx_hash", Numeric.toHexStringNoPrefix(tx.txHash()));

        return obj;
    }

    private static JsonObject serializeReceipt(TransactionReceipt receipt) {
        JsonObject obj = new JsonObject();
        obj.addProperty("tx_hash", Numeric.toHexStringNoPrefix(receipt.getTxHash()));
        obj.addProperty("success", receipt.isSuccess());
        obj.addProperty("gas_used", receipt.getGasUsed().toString());
        obj.addProperty("fee", receipt.getFee().toString());

        if (receipt.getError() != null) {
            obj.addProperty("error", receipt.getError());
        }

        if (receipt.getContractAddress() != null) {
            obj.addProperty("contract_address", receipt.getContractAddress().toHexString());
        }

        if (receipt.getReturnData() != null && receipt.getReturnData().length > 0) {
            obj.addProperty("return_data", "0x" + Numeric.toHexStringNoPrefix(receipt.getReturnData()));
        }

        return obj;
    }

    // --- Deserialize ---

    public static BlockChainBlock fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return fromJsonObject(root);
    }

    public static BlockChainBlock fromJsonObject(JsonObject root) {
        String blockHash = root.get("block_hash").getAsString();

        String previousBlockHash = null;
        if (root.has("previous_block_hash") && !root.get("previous_block_hash").isJsonNull()) {
            previousBlockHash = root.get("previous_block_hash").getAsString();
        }

        int blockNumber = root.has("block_number") ? root.get("block_number").getAsInt() : 0;

        Address proposer = null;
        if (root.has("proposer") && !root.get("proposer").isJsonNull()) {
            proposer = Address.fromHexString(root.get("proposer").getAsString());
        }

        // Transactions
        List<Transaction> transactions = new ArrayList<>();
        if (root.has("transactions") && !root.get("transactions").isJsonNull()) {
            for (JsonElement elem : root.getAsJsonArray("transactions")) {
                transactions.add(deserializeTransaction(elem.getAsJsonObject()));
            }
        }

        // Receipts
        List<TransactionReceipt> receipts = new ArrayList<>();
        if (root.has("receipts") && !root.get("receipts").isJsonNull()) {
            for (JsonElement elem : root.getAsJsonArray("receipts")) {
                receipts.add(deserializeReceipt(elem.getAsJsonObject()));
            }
        }

        String stateHash = null;
        if (root.has("state_hash") && !root.get("state_hash").isJsonNull()) {
            stateHash = root.get("state_hash").getAsString();
        }

        return new BlockChainBlock(blockHash, previousBlockHash, transactions, receipts, blockNumber, proposer, stateHash);
    }

    private static Transaction deserializeTransaction(JsonObject obj) {
        Address from = Address.fromHexString(obj.get("from").getAsString());

        Address to = null;
        if (obj.has("to") && !obj.get("to").isJsonNull()) {
            to = Address.fromHexString(obj.get("to").getAsString());
        }

        BigInteger value = BigInteger.ZERO;
        if (obj.has("value") && !obj.get("value").isJsonNull()) {
            value = new BigInteger(obj.get("value").getAsString());
        }

        byte[] data = new byte[0];
        if (obj.has("input") && !obj.get("input").isJsonNull()) {
            data = Numeric.hexStringToByteArray(obj.get("input").getAsString());
        }

        BigInteger gasPrice = BigInteger.ONE;
        if (obj.has("gas_price") && !obj.get("gas_price").isJsonNull()) {
            gasPrice = new BigInteger(obj.get("gas_price").getAsString());
        }

        BigInteger gasLimit = BigInteger.valueOf(21_000);
        if (obj.has("gas_limit") && !obj.get("gas_limit").isJsonNull()) {
            gasLimit = new BigInteger(obj.get("gas_limit").getAsString());
        }

        long nonce = 0;
        if (obj.has("nonce") && !obj.get("nonce").isJsonNull()) {
            nonce = obj.get("nonce").getAsLong();
        }

        byte[] signature = null;
        if (obj.has("signature") && !obj.get("signature").isJsonNull()) {
            signature = Numeric.hexStringToByteArray(obj.get("signature").getAsString());
        }

        String kind = obj.has("kind") && !obj.get("kind").isJsonNull()
                ? obj.get("kind").getAsString()
                : "STANDARD";

        if ("NATIVE_BALANCE_QUERY".equalsIgnoreCase(kind)) {
            Address target = null;
            if (obj.has("native_balance_query_target") && !obj.get("native_balance_query_target").isJsonNull()) {
                target = Address.fromHexString(obj.get("native_balance_query_target").getAsString());
            }
            return Transaction.nativeBalanceQuery(from, target, gasPrice, gasLimit, nonce, signature);
        }

        return new Transaction(from, to, value, data, gasPrice, gasLimit, nonce, signature);
    }

    private static TransactionReceipt deserializeReceipt(JsonObject obj) {
        byte[] txHash = Numeric.hexStringToByteArray(obj.get("tx_hash").getAsString());
        boolean success = obj.get("success").getAsBoolean();
        BigInteger gasUsed = new BigInteger(obj.get("gas_used").getAsString());
        BigInteger fee = new BigInteger(obj.get("fee").getAsString());

        String error = null;
        if (obj.has("error") && !obj.get("error").isJsonNull()) {
            error = obj.get("error").getAsString();
        }

        Address contractAddress = null;
        if (obj.has("contract_address") && !obj.get("contract_address").isJsonNull()) {
            contractAddress = Address.fromHexString(obj.get("contract_address").getAsString());
        }

        byte[] returnData = new byte[0];
        if (obj.has("return_data") && !obj.get("return_data").isJsonNull()) {
            returnData = Numeric.hexStringToByteArray(obj.get("return_data").getAsString());
        }

        return new TransactionReceipt(txHash, success, gasUsed, fee, error, returnData, contractAddress);
    }
}
