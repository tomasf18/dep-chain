package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.core.blockchain.Block;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.GenesisLoader;
import ist.depchain.core.hotstuff.tsignatures.BLSManager;
import ist.depchain.core.hotstuff.tsignatures.BLSThresholdSig;
import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.crypto.Authenticator;

public class ServerContext {
    private static final String DEFAULT_GENESIS_PATH = "core/src/main/resources/genesis.json";

    private final Config config;

    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink perfectLink;

    private BlockChain blockChain;
    private CommandExecutor commandExecutor;
    private DepChainWorldState worldState;

    private final BLSThresholdSig blsThresholdSig;

    public ServerContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        perfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));
        blockChain = new BlockChain();
        commandExecutor = new CommandExecutor(blockChain);
        worldState = new DepChainWorldState();
        loadGenesis();
        BLSManager.init();
        this.blsThresholdSig = new BLSThresholdSig(config);
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

    public CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    public DepChainWorldState getWorldState() {
        return worldState;
    }

    public BLSThresholdSig getBlsThresholdSig() {
        return blsThresholdSig;
    }
}