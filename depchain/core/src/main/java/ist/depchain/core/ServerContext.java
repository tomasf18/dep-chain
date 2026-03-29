package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.Erc20Abi;
import ist.depchain.common.utils.ProcessInfo;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.EvmService;
import ist.depchain.core.blockchain.GenesisLoader;
import ist.depchain.core.blockchain.TransactionExecutor;
import ist.depchain.core.hotstuff.tsignatures.BLSManager;
import ist.depchain.core.hotstuff.tsignatures.BLSThresholdSig;
import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.crypto.Authenticator;
import ist.depchain.network.crypto.KeyLoader;

import java.security.PublicKey;

import org.hyperledger.besu.datatypes.Address;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.web3j.crypto.Hash;

import java.math.BigInteger;

public class ServerContext {
    private final Config config;

    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink authenticatedPerfectLink;

    private BlockChain blockChain;
    private DepChainWorldState worldState;
    private TransactionExecutor transactionExecutor;

    private final BLSThresholdSig blsThresholdSig;

    public ServerContext(Config config) {
        this.config = config;

        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        authenticatedPerfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));

        blockChain = new BlockChain(config.getSelfId());
        worldState = new DepChainWorldState(config);
        transactionExecutor = new TransactionExecutor();
        loadGenesis();

        BLSManager.init();
        this.blsThresholdSig = new BLSThresholdSig(config);
    }

    private void loadGenesis() {
        try {
            BlockChainBlock genesis = GenesisLoader.loadGenesis(worldState, config);
            blockChain.addBlock(genesis);

            Address istAddress = config.getIstContractAddress();
            if (!worldState.accountExists(istAddress)) {
                throw new IllegalStateException("IST contract account missing after bootstrap");
            }
            if (worldState.getCode(istAddress) == null || worldState.getCode(istAddress).isEmpty()) {
                throw new IllegalStateException("IST contract code missing after bootstrap");
            }

            System.out.println("[SERVER_CONTEXT] Genesis block loaded with " + genesis.getTransactions().size() + " transactions \nIST contract bootstrapped at " + istAddress.toHexString());
            
            System.out.println("\n=== After Deployment ===");
            EvmService.printAccount(worldState.getSimpleWorld(), config.getInitialTokenHolderAddress(), "InitialTokenHolder");
            EvmService.printAccount(worldState.getSimpleWorld(), config.getIstContractAddress(), "Contract");
            for (Address addr : worldState.getTrackedAccounts()) {
                if (addr.equals(config.getInitialTokenHolderAddress()) || addr.equals(config.getIstContractAddress())) {
                    continue; // already printed above
                }
                ProcessInfo processInfo = config.getProcessByAddress(addr);
                String processId = processInfo != null ? processInfo.getId() : "Unknown";
                EvmService.printAccount(worldState.getSimpleWorld(), addr, processId);
            }
            System.out.println();

            MutableAccount contractAccount = (MutableAccount) worldState.getSimpleWorld().get(config.getIstContractAddress());

            String name = EvmService.callString(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), Erc20Abi.smartContractMethodIdentifier("name()"));
            String symbol = EvmService.callString(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), Erc20Abi.smartContractMethodIdentifier("symbol()"));
            BigInteger decimals = EvmService.callUint(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), Erc20Abi.smartContractMethodIdentifier("decimals()"));
            BigInteger totalSupply = EvmService.callUint(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), Erc20Abi.smartContractMethodIdentifier("totalSupply()"));

            String balanceOfInitialTokenHolderCalldata = Erc20Abi.smartContractMethodIdentifier("balanceOf(address)") + Erc20Abi.encodeAddress(config.getInitialTokenHolderAddress());
            BigInteger initialTokenHolderBalance = EvmService.callUint(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), balanceOfInitialTokenHolderCalldata);

            System.out.println("=== ERC-20 Read Calls ===");
            System.out.println("name():        " + name);
            System.out.println("symbol():      " + symbol);
            System.out.println("decimals():    " + decimals);
            System.out.println("totalSupply(): " + totalSupply);
            System.out.println("balanceOf(initialTokenHolder): " + initialTokenHolderBalance);
            System.out.println();

            trackErc20StorageSlots();

        } catch (Exception e) {
            System.err.println("[SERVER_CONTEXT | WARN] Could not load genesis: "
                    + e.getMessage() + " - starting with empty state");
        }
    }

    private void trackErc20StorageSlots() {
        Address contractAddress = config.getIstContractAddress();

        // Track every token holder balance slot and every client-to-client allowance slot we need to preserve.
        for (ProcessInfo processInfo : config.getProcesses().values()) {
            Address address = processInfo.getAddress();
            worldState.trackStorageSlot(contractAddress, balanceSlot(address));
            if (!processInfo.isClient()) {
                continue;
            }

            for (ProcessInfo spenderInfo : config.getProcesses().values()) {
                if (!spenderInfo.isClient()) {
                    continue;
                }
                worldState.trackStorageSlot(contractAddress, allowanceSlot(address, spenderInfo.getAddress()));
            }
        }

        worldState.refreshTrackedStorage(contractAddress);
    }

    private static UInt256 balanceSlot(Address owner) {
        // Solidity mapping layout: keccak256(padded owner || padded slotIndex).
        return UInt256.fromBytes(Bytes.wrap(Hash.sha3(concat(padAddress(owner), padWord(BigInteger.ZERO)))));
    }

    private static UInt256 allowanceSlot(Address owner, Address spender) {
        // Allowances live in a nested mapping, so we hash the spender on top of the owner's mapping slot.
        byte[] outer = Hash.sha3(concat(padAddress(owner), padWord(BigInteger.ONE)));
        return UInt256.fromBytes(Bytes.wrap(Hash.sha3(concat(padAddress(spender), outer))));
    }

    private static byte[] padAddress(Address address) {
        // Left-pad the 20-byte address to the 32-byte ABI word Solidity expects.
        byte[] out = new byte[32];
        byte[] raw = address.toArrayUnsafe();
        System.arraycopy(raw, 0, out, 12, raw.length);
        return out;
    }

    private static byte[] padWord(BigInteger value) {
        // Encode a small integer as a 32-byte big-endian ABI word.
        byte[] raw = value == null ? new byte[0] : value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            raw = trimmed;
        }

        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    public void start() throws Exception {
        authenticatedPerfectLink.start();
    }

    public void stop() throws Exception {
        authenticatedPerfectLink.stop();
    }

    /* Getters */
    public Config getConfig() {
        return config;
    }

    public AuthenticatedPerfectLink getPerfectLink() {
        return authenticatedPerfectLink;
    }

    public BlockChain getBlockChain() {
        return blockChain;
    }

    public DepChainWorldState getWorldState() {
        return worldState;
    }

    public TransactionExecutor getTransactionExecutor() {
        return transactionExecutor;
    }

    public BLSThresholdSig getBlsThresholdSig() {
        return blsThresholdSig;
    }

    public Address getSelfAddress() {
        return config.getSelfAddress();
    }

    /**
     * Derive the Ethereum-style address for a given replica/process by loading
     * its trusted public key.
     */
    public Address deriveAddressForProcess(String processId) {
        if (processId != null && processId.equals(config.getSelfId())) {
            return config.getSelfAddress();
        }

        String keyPath = config.getTrustedProcessKeyPathString(processId);
        PublicKey pubKey = KeyLoader.loadPublicKey(keyPath);
        if (pubKey == null) {
            return null;
        }
        return AddressUtils.deriveAddress(pubKey);
    }
}