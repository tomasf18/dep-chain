package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.AddressUtils;
import ist.depchain.core.blockchain.Block;
import ist.depchain.core.blockchain.DepChainWorldState;
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

public class ServerContext {
    private static final String DEFAULT_GENESIS_PATH = "core/src/main/resources/genesis.json";

    private final Config config;

    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink perfectLink;

    private BlockChain blockChain;
    private DepChainWorldState worldState;
    private TransactionExecutor transactionExecutor;

    private final BLSThresholdSig blsThresholdSig;
    private final Address selfAddress;

    public ServerContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        perfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));
        blockChain = new BlockChain("data/blocks/" + config.getSelfId());
        worldState = new DepChainWorldState();
        transactionExecutor = new TransactionExecutor(worldState);
        loadGenesis();
        BLSManager.init();
        this.blsThresholdSig = new BLSThresholdSig(config);
        PublicKey pubKey = KeyLoader.loadPublicKey(config.getSelfPublicKeyPathString());
        this.selfAddress = (pubKey != null) ? AddressUtils.deriveAddress(pubKey) : null;
    }

    private void loadGenesis() {
        try {
            Block genesis = GenesisLoader.loadGenesis(DEFAULT_GENESIS_PATH, worldState);
            blockChain.addBlock(genesis);
            System.out.println("[SERVER_CONTEXT] Genesis block loaded with "
                    + genesis.getTransactions().size() + " transactions");
        } catch (Exception e) {
            System.err.println("[SERVER_CONTEXT | WARN] Could not load genesis: " + e.getMessage()
                    + " — starting with empty state");
        }
    }

    public void start() throws Exception {
        perfectLink.start();
    }

    public void stop() throws Exception {
        perfectLink.stop();
    }

    /* Getters */
    public Config getConfig() {
        return config;
    }

    public AuthenticatedPerfectLink getPerfectLink() {
        return perfectLink;
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
        return selfAddress;
    }

    /**
     * Derive the Ethereum-style address for a given replica/process by loading
     * its trusted public key.
     */
    public Address deriveAddressForProcess(String processId) {
        String keyPath = config.getTrustedProcessKeyPathString(processId);
        PublicKey pk = KeyLoader.loadPublicKey(keyPath);
        if (pk == null) return null;
        return AddressUtils.deriveAddress(pk);
    }
}