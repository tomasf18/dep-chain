package ist.depchain.network.interfaces;

public interface SendHandle {
    void cancel(); // handler for canceling the send retransmission task (used by StubbornLink)
}
