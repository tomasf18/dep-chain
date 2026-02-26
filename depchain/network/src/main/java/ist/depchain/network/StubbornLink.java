package ist.depchain.network;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StubbornLink implements Link {

    private final Link underlyingLink;  // FairLossLink
    private final ScheduledExecutorService scheduler; // for the retransmission task
    private final long resendPeriodMillis;

    public StubbornLink(Link underlyingLink, long resendPeriodMillis) {
        this.underlyingLink = underlyingLink;
        this.resendPeriodMillis = resendPeriodMillis;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }

    @Override
    public void send(String destinationId, byte[] payload) {
        scheduler.scheduleAtFixedRate(() -> {
            underlyingLink.send(destinationId, payload);
        }, 0, resendPeriodMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void registerReceiver(MessageHandler handler) {
        underlyingLink.registerReceiver(handler);
    }

    @Override
    public void start() {
        underlyingLink.start();
    }

    @Override
    public void stop() {
        scheduler.shutdownNow();
        underlyingLink.stop();
    }
}