package ist.depchain.core;

// ALL ALGORITHM COORDINATION (E.G., LEADER ROTATION, TIMEOUTS, ETC.) SHOULD BE HANDLED IN THIS CLASS
public class HotStuffCoordinator {
    private final ServerContext serverContext;

    public HotStuffCoordinator(ServerContext serverContext) {
        this.serverContext = serverContext;
    }
}
