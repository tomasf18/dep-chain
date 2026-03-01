package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.network.abstractions.PerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.crypto.Authenticator;

public class ServerContext {
    private final Config config;

    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final PerfectLink perfectLink; // pass authenticator to perfect link for activating APL features

    public ServerContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        perfectLink = new PerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));
    }

    public void start() throws Exception {
        perfectLink.start();
        // loop();
    }

    /* Getters */
    public Config getConfig() {
        return config;
    }

    public PerfectLink getPerfectLink() {
        return perfectLink;
    }
        
}