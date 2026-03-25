package ist.depchain.core.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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

public final class EvmService {

    private EvmService() {}

    public static final class EvmResult {
        private final boolean success;
        private final Bytes returnData;
        private final Bytes runtimeCode;   // only meaningful for deployment
        private final String errorMessage;

        public EvmResult(boolean success, Bytes returnData, Bytes runtimeCode, String errorMessage) {
            this.success = success;
            this.returnData = returnData != null ? returnData : Bytes.EMPTY;
            this.runtimeCode = runtimeCode != null ? runtimeCode : Bytes.EMPTY;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return success;
        }

        public Bytes getReturnData() {
            return returnData;
        }

        public Bytes getRuntimeCode() {
            return runtimeCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Deterministic contract address derivation for normal on-chain deployments.
     * Simple deterministic rule: keccak256(deployer || nonce)[12..31]
     *
     * Bootstrap/genesis may still use a fixed configured address instead.
     */
    public static Address deriveContractAddress(Address deployer, long nonce) {
        byte[] deployerBytes = deployer.toArrayUnsafe();
        byte[] nonceBytes = ByteBuffer.allocate(Long.BYTES).putLong(nonce).array();

        ByteBuffer buf = ByteBuffer.allocate(deployerBytes.length + nonceBytes.length);
        buf.put(deployerBytes);
        buf.put(nonceBytes);

        byte[] hash = Hash.sha3(buf.array());
        byte[] addressBytes = Arrays.copyOfRange(hash, 12, 32);
        return Address.wrap(Bytes.wrap(addressBytes));
    }

    /**
     * Deploys a contract by executing creation bytecode (+ constructor args) into a given address.
     * Used for bootstrap/genesis or any deterministic project-specific deployment flow.
     */
    public static EvmResult deployContract(SimpleWorld world, Address sender, Address contractAddress, byte[] deploymentData) {

        ByteArrayOutputStream traceOutput = new ByteArrayOutputStream();
        PrintStream tracePrint = new PrintStream(traceOutput);
        StandardJsonTracer tracer = new StandardJsonTracer(tracePrint, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);

        if (world.get(contractAddress) == null) {
            world.createAccount(contractAddress, 0, Wei.ZERO);
        }

        executor.sender(sender);
        executor.receiver(contractAddress);
        executor.worldUpdater(world.updater());
        executor.commitWorldState();

        executor.code(Bytes.wrap(deploymentData));
        executor.callData(Bytes.EMPTY);
        executor.execute();

        JsonObject lastTrace = getLastTraceObject(traceOutput);
        String opName = lastTrace.get("opName").getAsString();
        Bytes returnBytes = extractReturnBytes(traceOutput);

        if ("REVERT".equalsIgnoreCase(opName)) {
            return new EvmResult(
                    false,
                    returnBytes,
                    Bytes.EMPTY,
                    decodeRevertReason(returnBytes)
            );
        }

        if (!"RETURN".equalsIgnoreCase(opName)) {
            return new EvmResult(
                    false,
                    returnBytes,
                    Bytes.EMPTY,
                    "unexpected final EVM op: " + opName
            );
        }

        MutableAccount contractAccount = (MutableAccount) world.get(contractAddress);
        if (contractAccount == null) {
            return new EvmResult(false, Bytes.EMPTY, Bytes.EMPTY, "contract account was not created");
        }

        contractAccount.setCode(returnBytes);

        return new EvmResult(true, Bytes.EMPTY, returnBytes, null);
    }

    /**
     * Executes a contract call (may mutate storage if calldata targets a state-changing function).
     */
    public static EvmResult callContract(SimpleWorld world, Address sender, Address contractAddress, Bytes runtimeCode, byte[] calldata) {

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
        executor.callData(Bytes.wrap(calldata));

        executor.execute();

        JsonObject lastTrace = getLastTraceObject(traceOutput);
        String opName = lastTrace.get("opName").getAsString();
        Bytes returnBytes = extractReturnBytes(traceOutput);

        if ("REVERT".equalsIgnoreCase(opName)) {
            return new EvmResult(
                    false,
                    returnBytes,
                    Bytes.EMPTY,
                    decodeRevertReason(returnBytes)
            );
        }

        if (!"RETURN".equalsIgnoreCase(opName)) {
            return new EvmResult(
                    false,
                    returnBytes,
                    Bytes.EMPTY,
                    "unexpected final EVM op: " + opName
            );
        }

        return new EvmResult(true, returnBytes, Bytes.EMPTY, null);
    }

    // ---------- Convenience read helpers ----------

    public static String callString(SimpleWorld world, Address sender, Address contractAddress, Bytes runtimeCode, String calldataHex) {

        EvmResult result = callContract(world, sender, contractAddress, runtimeCode, Bytes.fromHexString(calldataHex).toArrayUnsafe());
        if (!result.isSuccess()) {
            throw new IllegalStateException("EVM string call failed: " + result.getErrorMessage());
        }
        return decodeAbiString(result.getReturnData());
    }

    public static BigInteger callUint(SimpleWorld world, Address sender, Address contractAddress, Bytes runtimeCode, String calldataHex) {

        EvmResult result = callContract(world, sender, contractAddress, runtimeCode, Bytes.fromHexString(calldataHex).toArrayUnsafe());
        if (!result.isSuccess()) {
            throw new IllegalStateException("EVM uint call failed: " + result.getErrorMessage());
        }
        return decodeAbiUint(result.getReturnData());
    }

    // ---------- Artifact / ABI helpers ----------

    public static String readHexFile(String path) throws IOException {
        String raw = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
        if (raw.startsWith("0x") || raw.startsWith("0X")) {
            raw = raw.substring(2);
        }
        return raw.replaceAll("\\s+", "");
    }

    public static String selector(String signature) {
        String hashHex = Hash.sha3String(signature);
        return hashHex.substring(2, 10);
    }

    public static String encodeAddressArgument(Address address) {
        String hex = address.toHexString().substring(2);
        return "000000000000000000000000" + hex;
    }

    public static String encodeUint256Argument(BigInteger value) {
        String hex = value.toString(16);
        if (hex.length() > 64) {
            throw new IllegalArgumentException("uint256 value too large");
        }
        return "0".repeat(64 - hex.length()) + hex;
    }

    public static String encodeAddressAndUint256(Address address, BigInteger value) {
        return encodeAddressArgument(address) + encodeUint256Argument(value);
    }

    public static String encodeTwoAddresses(Address a1, Address a2) {
        return encodeAddressArgument(a1) + encodeAddressArgument(a2);
    }

    // ---------- Decoding ----------

    public static Bytes extractReturnBytes(ByteArrayOutputStream output) {
        JsonObject json = getLastTraceObject(output);

        String memory = json.get("memory").getAsString();
        JsonArray stack = json.get("stack").getAsJsonArray();

        int offset = decodeHexInt(stack.get(stack.size() - 1).getAsString());
        int size = decodeHexInt(stack.get(stack.size() - 2).getAsString());

        if (size == 0) {
            return Bytes.EMPTY;
        }

        String returnData = memory.substring(
                2 + offset * 2,
                2 + offset * 2 + size * 2
        );

        return Bytes.fromHexString("0x" + returnData);
    }

    public static String decodeAbiString(Bytes returnData) {
        if (returnData == null || returnData.isEmpty()) {
            return "";
        }

        String hex = returnData.toHexString().substring(2);

        int stringOffset = Integer.parseInt(hex.substring(0, 64), 16);
        int stringLength = Integer.parseInt(
                hex.substring(stringOffset * 2, stringOffset * 2 + 64), 16);

        String hexString = hex.substring(
                stringOffset * 2 + 64,
                stringOffset * 2 + 64 + stringLength * 2
        );

        return new String(hexStringToByteArray(hexString), StandardCharsets.UTF_8);
    }

    public static BigInteger decodeAbiUint(Bytes returnData) {
        if (returnData == null || returnData.isEmpty()) {
            return BigInteger.ZERO;
        }
        String hex = returnData.toHexString().substring(2);
        return new BigInteger(hex, 16);
    }

    /**
     * Decode standard Solidity Error(string) revert payload if present.
     */
    public static String decodeRevertReason(Bytes returnData) {
        if (returnData == null || returnData.isEmpty()) {
            return "EVM revert";
        }

        String hex = returnData.toHexString().substring(2);
        if (hex.length() < 8) {
            return "EVM revert";
        }

        // Error(string) selector = 0x08c379a0
        if (!hex.startsWith("08c379a0")) {
            return "EVM revert: " + returnData.toHexString();
        }

        try {
            String payload = hex.substring(8); // strip selector
            int stringOffset = Integer.parseInt(payload.substring(0, 64), 16);
            int stringLength = Integer.parseInt(
                    payload.substring(stringOffset * 2, stringOffset * 2 + 64), 16);

            String strHex = payload.substring(
                    stringOffset * 2 + 64,
                    stringOffset * 2 + 64 + stringLength * 2
            );

            return new String(hexStringToByteArray(strHex), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "EVM revert";
        }
    }

    // ---------- Trace helpers ----------

    public static JsonObject getLastTraceObject(ByteArrayOutputStream output) {
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && line.startsWith("{")) {
                return JsonParser.parseString(line).getAsJsonObject();
            }
        }
        throw new IllegalStateException("No JSON trace line found in Besu tracer output");
    }

    public static int decodeHexInt(String value) {
        return Integer.decode(value);
    }

    public static byte[] hexStringToByteArray(String hexString) {
        int length = hexString.length();
        byte[] byteArray = new byte[length / 2];

        for (int i = 0; i < length; i += 2) {
            int value = Integer.parseInt(hexString.substring(i, i + 2), 16);
            byteArray[i / 2] = (byte) value;
        }

        return byteArray;
    }

    // ---------- Debug ----------

    public static void printAccount(SimpleWorld world, Address address, String label) {
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

    public static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Assertion failed for " + label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}