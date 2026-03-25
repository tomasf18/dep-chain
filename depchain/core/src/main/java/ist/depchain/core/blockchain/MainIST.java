package ist.depchain.core.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.web3j.crypto.Hash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class MainIST {

    private static final String CREATION_BIN_PATH = "./src/main/resources/contracts/ist/ISTCoin.creation.bin";

    private static final Address INITIAL_TOKEN_HOLDER = Address.fromHexString("0x1111111111111111111111111111111111111111");
    private static final Address CONTRACT_ADDRESS = Address.fromHexString("0x9999999999999999999999999999999999999999");

    public static void main(String[] args) throws Exception {
        // 1. Create a fresh in-memory world
        SimpleWorld world = new SimpleWorld();

        // 2. Create the deployer/owner account with plenty of native balance
        world.createAccount(INITIAL_TOKEN_HOLDER, 0, Wei.of(BigInteger.valueOf(1_000_000_000L)));

        System.out.println("=== Initial World State ===");
        printAccount(world, INITIAL_TOKEN_HOLDER, "InitialTokenHolder");
        System.out.println();

        // 3. Load creation bytecode and append ABI-encoded constructor arg
        String creationBytecode = readHexFile(CREATION_BIN_PATH);
        String constructorArg = encodeAddressArgument(INITIAL_TOKEN_HOLDER);
        String deploymentDataHex = creationBytecode + constructorArg;

        // 4. Deploy contract
        deployContract(world, INITIAL_TOKEN_HOLDER, CONTRACT_ADDRESS, deploymentDataHex);

        System.out.println("=== After Deployment ===");
        printAccount(world, INITIAL_TOKEN_HOLDER, "InitialTokenHolder");
        printAccount(world, CONTRACT_ADDRESS, "Contract");
        System.out.println();

        MutableAccount contractAccount = (MutableAccount) world.get(CONTRACT_ADDRESS);
        if (contractAccount == null) {
            throw new IllegalStateException("Contract account was not created");
        }
        if (contractAccount.getCode() == null || contractAccount.getCode().isEmpty()) {
            throw new IllegalStateException("Deployment failed: contract runtime code is empty");
        }

        // 5. Read-only calls
        String name = callString(world, INITIAL_TOKEN_HOLDER, CONTRACT_ADDRESS, contractAccount.getCode(), selector("name()"));
        String symbol = callString(world, INITIAL_TOKEN_HOLDER, CONTRACT_ADDRESS, contractAccount.getCode(), selector("symbol()"));
        BigInteger decimals = callUint(world, INITIAL_TOKEN_HOLDER, CONTRACT_ADDRESS, contractAccount.getCode(), selector("decimals()"));
        BigInteger totalSupply = callUint(world, INITIAL_TOKEN_HOLDER, CONTRACT_ADDRESS, contractAccount.getCode(), selector("totalSupply()"));

        String balanceOfInitialTokenHolderCalldata = selector("balanceOf(address)") + encodeAddressArgument(INITIAL_TOKEN_HOLDER);
        BigInteger initialTokenHolderBalance = callUint(world, INITIAL_TOKEN_HOLDER, CONTRACT_ADDRESS, contractAccount.getCode(), balanceOfInitialTokenHolderCalldata);

        // 6. Print results
        System.out.println("=== ERC-20 Read Calls ===");
        System.out.println("name():        " + name);
        System.out.println("symbol():      " + symbol);
        System.out.println("decimals():    " + decimals);
        System.out.println("totalSupply(): " + totalSupply);
        System.out.println("balanceOf(initialTokenHolder): " + initialTokenHolderBalance);
        System.out.println();

        // 7. Expected values for the contract we designed
        BigInteger expectedSupply = new BigInteger("10000000000"); // 100,000,000 * 10^2
        assertEquals("IST Coin", name, "name()");
        assertEquals("IST", symbol, "symbol()");
        assertEquals(BigInteger.valueOf(2), decimals, "decimals()");
        assertEquals(expectedSupply, totalSupply, "totalSupply()");
        assertEquals(expectedSupply, initialTokenHolderBalance, "balanceOf(initialTokenHolder)");

        System.out.println("All checks passed.");
    }

    private static void deployContract(SimpleWorld world, Address sender, Address contractAddress, String deploymentDataHex) {

        ByteArrayOutputStream traceOutput = new ByteArrayOutputStream();
        PrintStream tracePrint = new PrintStream(traceOutput);
        StandardJsonTracer tracer = new StandardJsonTracer(tracePrint, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);

        // Create the destination account where constructor writes storage
        world.createAccount(contractAddress, 0, Wei.ZERO);

        executor.sender(sender);
        executor.receiver(contractAddress);
        executor.worldUpdater(world.updater());
        executor.commitWorldState();

        // Run creation bytecode + encoded constructor args
        executor.code(Bytes.fromHexString(deploymentDataHex));
        executor.callData(Bytes.EMPTY);

        executor.execute();

        printLastTraceLine(traceOutput, "Deployment");

        // IMPORTANT:
        // The constructor RETURN contains the runtime bytecode.
        // We must install it manually into the account for this standalone test.
        Bytes runtimeCode = extractReturnBytes(traceOutput);

        MutableAccount contractAccount = (MutableAccount) world.get(contractAddress);
        if (contractAccount == null) {
            throw new IllegalStateException("Contract account was not created");
        }

        contractAccount.setCode(runtimeCode);
    }

    private static Bytes extractReturnBytes(ByteArrayOutputStream output) {
        JsonObject json = getLastTraceObject(output);

        String memory = json.get("memory").getAsString();
        JsonArray stack = json.get("stack").getAsJsonArray();

        int offset = decodeHexInt(stack.get(stack.size() - 1).getAsString());
        int size = decodeHexInt(stack.get(stack.size() - 2).getAsString());

        String returnData = memory.substring(
                2 + offset * 2,
                2 + offset * 2 + size * 2
        );

        return Bytes.fromHexString("0x" + returnData);
    }

    private static String callString(SimpleWorld world, Address sender, Address contractAddress, Bytes runtimeCode, String calldataHex) {
        ByteArrayOutputStream traceOutput = new ByteArrayOutputStream();
        PrintStream tracePrint = new PrintStream(traceOutput);
        StandardJsonTracer tracer = new StandardJsonTracer(tracePrint, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.sender(sender);
        executor.receiver(contractAddress);
        executor.worldUpdater(world.updater());
        executor.commitWorldState();
        executor.code(runtimeCode);
        executor.callData(Bytes.fromHexString(calldataHex));

        executor.execute();

        return extractStringFromReturnData(traceOutput);
    }

    private static BigInteger callUint(SimpleWorld world, Address sender, Address contractAddress, Bytes runtimeCode, String calldataHex) {

        ByteArrayOutputStream traceOutput = new ByteArrayOutputStream();
        PrintStream tracePrint = new PrintStream(traceOutput);
        StandardJsonTracer tracer = new StandardJsonTracer(tracePrint, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.sender(sender);
        executor.receiver(contractAddress);
        executor.worldUpdater(world.updater());
        executor.commitWorldState();
        executor.code(runtimeCode);
        executor.callData(Bytes.fromHexString(calldataHex));

        executor.execute();

        return extractUintFromReturnData(traceOutput);
    }

    private static String readHexFile(String path) throws IOException {
        String raw = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
        if (raw.startsWith("0x") || raw.startsWith("0X")) {
            raw = raw.substring(2);
        }
        return raw.replaceAll("\\s+", "");
    }

    /**
     * Computes the 4-byte function selector from a Solidity function signature.
     * Example: selector("balanceOf(address)")
     */
    private static String selector(String signature) {
        String hashHex = Hash.sha3String(signature);
        return hashHex.substring(2, 10);
    }

    /**
     * ABI-encode a Solidity address argument as one 32-byte word.
     */
    private static String encodeAddressArgument(Address address) {
        String hex = address.toHexString().substring(2); // strip 0x
        return "000000000000000000000000" + hex;
    }

    public static String extractStringFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        String[] lines = byteArrayOutputStream.toString().split("\\r?\\n");
        JsonObject jsonObject = JsonParser.parseString(
            lines[lines.length - 1]
        ).getAsJsonObject();

        String memory = jsonObject.get("memory").getAsString();

        JsonArray stack = jsonObject.get("stack").getAsJsonArray();
        int offset = Integer.decode(stack.get(stack.size() - 1).getAsString());
        int size = Integer.decode(stack.get(stack.size() - 2).getAsString());

        String returnData = memory.substring(
            2 + offset * 2,
            2 + offset * 2 + size * 2
        );

        int stringOffset = Integer.decode(
            "0x" + returnData.substring(0, 32 * 2)
        );
        int stringLength = Integer.decode(
            "0x" +
                returnData.substring(
                    stringOffset * 2,
                    stringOffset * 2 + 32 * 2
                )
        );
        String hexString = returnData.substring(
            stringOffset * 2 + 32 * 2,
            stringOffset * 2 + 32 * 2 + stringLength * 2
        );

        return new String(
            hexStringToByteArray(hexString),
            StandardCharsets.UTF_8
        );
    }

    private static BigInteger extractUintFromReturnData(ByteArrayOutputStream output) {
        JsonObject json = getLastTraceObject(output);

        String memory = json.get("memory").getAsString();
        JsonArray stack = json.get("stack").getAsJsonArray();

        int offset = decodeHexInt(stack.get(stack.size() - 1).getAsString());
        int size = decodeHexInt(stack.get(stack.size() - 2).getAsString());

        String returnData = memory.substring(
                2 + offset * 2,
                2 + offset * 2 + size * 2
        );

        if (returnData.isEmpty()) {
            return BigInteger.ZERO;
        }

        return new BigInteger(returnData, 16);
    }

    private static JsonObject getLastTraceObject(ByteArrayOutputStream output) {
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && line.startsWith("{")) {
                return JsonParser.parseString(line).getAsJsonObject();
            }
        }
        throw new IllegalStateException("No JSON trace line found in Besu tracer output");
    }

    private static int decodeHexInt(String value) {
        return Integer.decode(value);
    }

    private static byte[] hexStringToByteArray(String hexString) {
        int length = hexString.length();
        byte[] byteArray = new byte[length / 2];

        for (int i = 0; i < length; i += 2) {
            int value = Integer.parseInt(hexString.substring(i, i + 2), 16);
            byteArray[i / 2] = (byte) value;
        }

        return byteArray;
    }

    private static void printAccount(SimpleWorld world, Address address, String label) {
        MutableAccount account = (MutableAccount) world.get(address);
        System.out.println(label + " Account");
        System.out.println("  Address: " + address);

        if (account == null) {
            System.out.println("  <does not exist>");
            return;
        }

        System.out.println("  Balance: " + account.getBalance());
        System.out.println("  Nonce: " + account.getNonce());
        System.out.println("  Code size: " + (account.getCode() == null ? 0 : account.getCode().size()));
    }

    private static void printLastTraceLine(ByteArrayOutputStream output, String label) {
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                System.out.println("=== " + label + " last trace ===");
                System.out.println(line);
                return;
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Assertion failed for " + label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}