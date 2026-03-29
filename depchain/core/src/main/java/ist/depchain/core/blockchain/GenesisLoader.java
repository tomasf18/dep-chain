package ist.depchain.core.blockchain;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.Config;

import org.hyperledger.besu.datatypes.Address;

/**
 * Loads the genesis block (block 0), initializes world state,
 * and executes the genesis deployment transaction(s).
 */
public class GenesisLoader {

    public static BlockChainBlock loadGenesis(DepChainWorldState worldState, Config config) throws IOException {
        Gson gson = new Gson();
        JsonObject root;

        String genesisFilePath = config.getGenesisPath();

        // First try loading from filesystem, then fallback to classpath resource
        File file = new File(genesisFilePath);
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                root = gson.fromJson(reader, JsonObject.class);
            }
        } else {
            String resourceName = genesisFilePath;
            int lastSlash = resourceName.lastIndexOf('/');
            if (lastSlash >= 0) resourceName = resourceName.substring(lastSlash + 1);
            int lastBackslash = resourceName.lastIndexOf('\\');
            if (lastBackslash >= 0) resourceName = resourceName.substring(lastBackslash + 1);

            InputStream is = GenesisLoader.class.getClassLoader().getResourceAsStream(resourceName);
            if (is == null) {
                throw new IOException("Genesis file not found on filesystem (" + genesisFilePath
                        + ") or classpath (" + resourceName + ")");
            }
            try (Reader reader = new InputStreamReader(is)) {
                root = gson.fromJson(reader, JsonObject.class);
            }
        }

        validateRoot(root);

        // 1. Load initial account state
        JsonObject state = root.getAsJsonObject("state");
        for (Map.Entry<String, JsonElement> accountEntry : state.entrySet()) {
            String addressString = accountEntry.getKey();
            Address address = parseAddress(addressString, "state account key");

            JsonObject accountData = requireObject(accountEntry.getValue(), "state entry for " + addressString);

            if (!accountData.has("balance")) {
                throw new IllegalArgumentException("Genesis state entry missing balance for address " + addressString);
            }

            BigInteger balance = parseNonNegativeBigInteger(accountData.get("balance").getAsString(), "balance for " + addressString);

            long nonce = 0;
            if (accountData.has("nonce")) {
                nonce = parseNonNegativeLong(accountData.get("nonce").getAsLong(), "nonce for " + addressString);
            }

            worldState.createEOA(address, nonce, balance);
        }

        // 2. Parse genesis transactions
        List<Transaction> transactions = new ArrayList<>();
        if (root.has("transactions") && !root.get("transactions").isJsonNull()) {
            JsonArray txArray = root.getAsJsonArray("transactions");
            for (JsonElement txElem : txArray) {
                JsonObject txObj = requireObject(txElem, "genesis transaction");
                transactions.add(parseTransaction(txObj));
            }
        }

        // 3. Execute genesis deployment transactions
        Address expectedContractAddress = config.getIstContractAddress();
        Address expectedInitialTokenHolderAddress = config.getInitialTokenHolderAddress();

        for (Transaction tx : transactions) {
            if (tx.isContractDeployment()) {
                if (!tx.getFrom().equals(expectedInitialTokenHolderAddress)) {
                    throw new IllegalArgumentException("Genesis deployment tx sender must equal treasuryAddress");
                }

                // Execute deployment into the fixed, known IST contract address
                EvmService.deployContract(worldState, tx.getFrom(), expectedContractAddress, tx.getData(), tx.getGasLimit());
            }
        }

        // 4. Verify that the contract was actually installed
        if (!worldState.accountExists(expectedContractAddress)) {
            throw new IllegalStateException("IST contract account does not exist after genesis deployment");
        }
        if (worldState.getCode(expectedContractAddress) == null || worldState.getCode(expectedContractAddress).isEmpty()) {
            throw new IllegalStateException("IST contract runtime code is empty after genesis deployment");
        }

        // 5. Build genesis block
        String blockHash = root.has("block_hash") && !root.get("block_hash").isJsonNull()
                ? root.get("block_hash").getAsString()
                : "0x0";

        String previousBlockHash = null;
        if (root.has("previous_block_hash") && !root.get("previous_block_hash").isJsonNull()) {
            previousBlockHash = root.get("previous_block_hash").getAsString();
        }

        if (previousBlockHash != null) {
            throw new IllegalArgumentException("Genesis block must have previous_block_hash = null");
        }

        return new BlockChainBlock(blockHash, null, transactions, 0);
    }

    private static void validateRoot(JsonObject root) {
        if (root == null) {
            throw new IllegalArgumentException("Genesis file is empty or invalid JSON");
        }
        if (!root.has("state") || root.get("state").isJsonNull()) {
            throw new IllegalArgumentException("Genesis file must contain a non-null 'state' object");
        }
        if (!root.get("state").isJsonObject()) {
            throw new IllegalArgumentException("'state' must be a JSON object");
        }
    }

    private static Transaction parseTransaction(JsonObject txObj) {
        if (!txObj.has("from") || txObj.get("from").isJsonNull()) {
            throw new IllegalArgumentException("Genesis transaction missing non-null 'from'");
        }

        Address from = parseAddress(txObj.get("from").getAsString(), "genesis tx.from");

        Address to = null;
        if (txObj.has("to") && !txObj.get("to").isJsonNull()) {
            to = parseAddress(txObj.get("to").getAsString(), "genesis tx.to");
        }

        BigInteger value = BigInteger.ZERO;
        if (txObj.has("value") && !txObj.get("value").isJsonNull()) {
            value = parseNonNegativeBigInteger(txObj.get("value").getAsString(), "genesis tx.value");
        }

        byte[] data = new byte[0];
        if (txObj.has("input") && !txObj.get("input").isJsonNull()) {
            data = hexStringToBytes(txObj.get("input").getAsString());
        }

        BigInteger gasPrice = BigInteger.ONE;
        if (txObj.has("gas_price") && !txObj.get("gas_price").isJsonNull()) {
            gasPrice = parsePositiveBigInteger(txObj.get("gas_price").getAsString(), "genesis tx.gas_price");
        }

        BigInteger gasLimit = BigInteger.valueOf(10_000_000);
        if (txObj.has("gas_limit") && !txObj.get("gas_limit").isJsonNull()) {
            gasLimit = parsePositiveBigInteger(txObj.get("gas_limit").getAsString(), "genesis tx.gas_limit");
        }

        long nonce = 0;
        if (txObj.has("nonce") && !txObj.get("nonce").isJsonNull()) {
            nonce = parseNonNegativeLong(txObj.get("nonce").getAsLong(), "genesis tx.nonce");
        }

        return new Transaction(from, to, value, data, gasPrice, gasLimit, nonce, null);
    }

    private static JsonObject requireObject(JsonElement element, String fieldName) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static Address parseAddress(String value, String fieldName) {
        try {
            return Address.fromHexString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid address in " + fieldName + ": " + value, e);
        }
    }

    private static BigInteger parseNonNegativeBigInteger(String value, String fieldName) {
        BigInteger result;
        try {
            result = new BigInteger(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid integer for " + fieldName + ": " + value, e);
        }
        if (result.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
        return result;
    }

    private static BigInteger parsePositiveBigInteger(String value, String fieldName) {
        BigInteger result = parseNonNegativeBigInteger(value, fieldName);
        if (result.signum() == 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }
        return result;
    }

    private static long parseNonNegativeLong(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
        return value;
    }

    private static byte[] hexStringToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.isEmpty()) {
            return new byte[0];
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