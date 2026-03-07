package ist.depchain.core.byzantine;

import ist.depchain.common.HotStuffMessage;
import ist.depchain.core.ServerContext;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

public class ByzantineCoordinator extends BasicHotStuffCoordinator {
    public ByzantineCoordinator(ServerContext serverContext) {
        super(serverContext, true);
    }
    @Override
    public void onReceivePrepareVote(String sourceId, HotStuffMessage message) {
        System.out.println("[BYZANTINE COORDINATOR] - Ignoring vote from: " + sourceId);
    }
}
