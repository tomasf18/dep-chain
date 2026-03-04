package ist.depchain.core;

import ist.depchain.common.utils.Config;
import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;
import ist.depchain.network.crypto.Authenticator;
import ist.depchain.network.interfaces.Link;

public class ServerContext {
    private final Config config;

    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final Link perfectLink;

    public ServerContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        perfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config, fairLossLink));
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

    public Link getPerfectLink() {
        return perfectLink;
    }

}