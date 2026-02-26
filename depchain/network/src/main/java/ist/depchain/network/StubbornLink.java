package ist.depchain.network;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

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
    public SendHandle send(String destinationId, byte[] payload) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            System.out.println("StubbornLink: Sending/Retransmitting message to " + destinationId);
            underlyingLink.send(destinationId, payload);
        }, 0, resendPeriodMillis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
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