package ist.depchain.network.abstractions;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import ist.depchain.network.utils.Config;
import ist.depchain.network.interfaces.Link;
import ist.depchain.network.interfaces.MessageHandler;
import ist.depchain.network.interfaces.SendHandle;

public class StubbornLink implements Link {

    private final Link fairLossLink;  
    private final Config config;
    private final ScheduledExecutorService scheduler; // for the retransmission task

    public StubbornLink(Config config, Link fairLossLink) {
        this.fairLossLink = fairLossLink;
        this.config = config; 
        this.scheduler = Executors.newScheduledThreadPool(1);
    }

    @Override
    public SendHandle send(String destinationId, byte[] payload) {
        long[] numberOfRetries = { 0 };
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {

            /*
            if (numberOfRetries[0] == 0) {
                System.out.println("StubbornLink: Sending message to " + destinationId); }
            else {
                System.out.println("StubbornLink: Retrying to send message to " + destinationId + ", retry count: " + numberOfRetries[0]); }
             */

            fairLossLink.send(destinationId, payload);
            numberOfRetries[0]++;
            
        }, 0, config.getResendPeriodMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void registerReceiver(MessageHandler handler) {
        fairLossLink.registerReceiver(handler);
    }

    @Override
    public void start() {
        fairLossLink.start();
    }

    @Override
    public void stop() {
        scheduler.shutdownNow();
        fairLossLink.stop();
    }
}