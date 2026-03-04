package ist.depchain.core;

import java.util.Timer;
import java.util.TimerTask;

// ALL ALGORITHM COORDINATION (E.G., LEADER ROTATION, TIMEOUTS, ETC.) SHOULD BE HANDLED IN THIS CLASS
public class HotStuffCoordinator {
    private final BasicHotStuffProtocol protocol;
    private final ServerContext serverContext;
    private Timer viewTimer;
    private static final long TIMEOUT_MS = 1000000;

    public HotStuffCoordinator(ServerContext serverContext) {
        this.serverContext = serverContext;
        this.protocol = new BasicHotStuffProtocol(serverContext);
        startTimer();
    }

    public void startTimer() {
        if (viewTimer != null) {viewTimer.cancel();}
        viewTimer = new Timer();
        viewTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("[TIMEOUT] - Starting new view");
                protocol.startNextView();
            }
        }, TIMEOUT_MS);
    }

    public void restartTimer() {
        startTimer();
    }

    public BasicHotStuffProtocol getProtocol() {
        return protocol;
    }
}
