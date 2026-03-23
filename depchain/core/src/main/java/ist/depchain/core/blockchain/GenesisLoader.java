package ist.depchain.core.blockchain;

import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.hyperledger.besu.datatypes.Address;

/**
 * Loads the genesis block (block 0) from a JSON file and initializes the world state.
 *
 * Genesis JSON format:
 * {
 *   "block_hash": "0x...",
 *   "transactions": [ { "from": "0x...", "to": null, "input": "0x..." } ],
 *   "state": {
 *     "0xAddress": { "balance": "100000", "nonce": 0 },
 *     ...
 *   }
 * }
 */
public class GenesisLoader {

    public static Block loadGenesis(String genesisFilePath, DepChainWorldState worldState) throws IOException {
        Gson gson = new Gson();
        JsonObject root;
        try (FileReader reader = new FileReader(genesisFilePath)) {
            root = gson.fromJson(reader, JsonObject.class);
        }

        // 1. Load initial account state
        JsonObject state = root.getAsJsonObject("state");
        for (Map.Entry<String, JsonElement> entry : state.entrySet()) {
            Address address = Address.fromHexString(entry.getKey());
            JsonObject accountData = entry.getValue().getAsJsonObject();

            BigInteger balance = new BigInteger(accountData.get("balance").getAsString());
            long nonce = accountData.has("nonce") ? accountData.get("nonce").getAsLong() : 0;

            worldState.createEOA(address, nonce, balance);
        }

        // 2. Parse transactions (e.g., ERC-20 deployment)
        List<Transaction> transactions = new ArrayList<>();
        if (root.has("transactions")) {
            JsonArray txArray = root.getAsJsonArray("transactions");
            for (JsonElement txElem : txArray) {
                JsonObject txObj = txElem.getAsJsonObject();
                transactions.add(parseTransaction(txObj));
            }
        }

        // 3. Build the genesis block
        String blockHash = root.has("block_hash") ? root.get("block_hash").getAsString() : "0x0";

        return new Block(blockHash, null, transactions, 0);
    }

    private static Transaction parseTransaction(JsonObject txObj) {
        Address from = Address.fromHexString(txObj.get("from").getAsString());

        Address to = null;
        if (txObj.has("to") && !txObj.get("to").isJsonNull()) {
            to = Address.fromHexString(txObj.get("to").getAsString());
        }

        BigInteger value = BigInteger.ZERO;
        if (txObj.has("value")) {
            value = new BigInteger(txObj.get("value").getAsString());
        }

        byte[] data = new byte[0];
        if (txObj.has("input")) {
            String hex = txObj.get("input").getAsString();
            data = hexStringToBytes(hex);
        }

        BigInteger gasPrice = BigInteger.ONE; // genesis txs use default gas
        if (txObj.has("gas_price")) {
            gasPrice = new BigInteger(txObj.get("gas_price").getAsString());
        }

        BigInteger gasLimit = BigInteger.valueOf(10_000_000); // generous default for deployment
        if (txObj.has("gas_limit")) {
            gasLimit = new BigInteger(txObj.get("gas_limit").getAsString());
        }

        long nonce = 0;
        if (txObj.has("nonce")) {
            nonce = txObj.get("nonce").getAsLong();
        }

        // Genesis transactions are pre-authorized, no signature needed
        return new Transaction(from, to, value, data, gasPrice, gasLimit, nonce, null);
    }

    private static byte[] hexStringToBytes(String hex) {
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
