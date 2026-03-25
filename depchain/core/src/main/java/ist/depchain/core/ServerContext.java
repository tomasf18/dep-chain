package ist.depchain.core;

import ist.depchain.common.utils.Config;
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
import org.hyperledger.besu.evm.account.MutableAccount;

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
        worldState = new DepChainWorldState();
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

            System.out.println("[SERVER_CONTEXT] Genesis block loaded with "
                    + genesis.getTransactions().size()
                    + " transactions; IST contract bootstrapped at "
                    + istAddress.toHexString());

            System.out.println("=== After Deployment ===");
            EvmService.printAccount(worldState.getSimpleWorld(), config.getInitialTokenHolderAddress(), "InitialTokenHolder");
            EvmService.printAccount(worldState.getSimpleWorld(), config.getIstContractAddress(), "Contract");
            System.out.println();

            MutableAccount contractAccount = (MutableAccount) worldState.getSimpleWorld().get(config.getIstContractAddress());
            // 5. Read-only calls
            String name = EvmService.callString(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), EvmService.selector("name()"));
            String symbol = EvmService.callString(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), EvmService.selector("symbol()"));
            BigInteger decimals = EvmService.callUint(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), EvmService.selector("decimals()"));
            BigInteger totalSupply = EvmService.callUint(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), EvmService.selector("totalSupply()"));

            String balanceOfInitialTokenHolderCalldata = EvmService.selector("balanceOf(address)") + EvmService.encodeAddressArgument(config.getInitialTokenHolderAddress());
            BigInteger initialTokenHolderBalance = EvmService.callUint(worldState, config.getInitialTokenHolderAddress(), config.getIstContractAddress(), contractAccount.getCode(), balanceOfInitialTokenHolderCalldata);

            // 6. Print results
            System.out.println("=== ERC-20 Read Calls ===");
            System.out.println("name():        " + name);
            System.out.println("symbol():      " + symbol);
            System.out.println("decimals():    " + decimals);
            System.out.println("totalSupply(): " + totalSupply);
            System.out.println("balanceOf(initialTokenHolder): " + initialTokenHolderBalance);
            System.out.println();

        } catch (Exception e) {
            System.err.println("[SERVER_CONTEXT | WARN] Could not load genesis: "
                    + e.getMessage() + " - starting with empty state");
        }
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
        String keyPath = config.getTrustedProcessKeyPathString(processId);
        PublicKey pubKey = KeyLoader.loadPublicKey(keyPath);
        if (pubKey == null) {
            return null;
        }
        return AddressUtils.deriveAddress(pubKey);
    }
}