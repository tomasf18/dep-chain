package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.core.hotstuff.tsignatures.BLSManager;
import ist.depchain.core.hotstuff.tsignatures.BLSThresholdSig;
import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.crypto.Authenticator;

public class ServerContext {
    private final Config config;

    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink perfectLink;

    private BlockChain blockChain;
    private CommandExecutor commandExecutor;

    private final BLSThresholdSig blsThresholdSig;

    public ServerContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        perfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config, fairLossLink));
        blockChain = new BlockChain();
        commandExecutor = new CommandExecutor(blockChain);
        BLSManager.init();
        int threshold = config.getF() + 1;
        this.blsThresholdSig = new BLSThresholdSig(config.getSelfKeysDirectory(), config.selfBlsIndex(), threshold);
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

    public BLSThresholdSig getBlsThresholdSig() {
        return blsThresholdSig;
    }
}