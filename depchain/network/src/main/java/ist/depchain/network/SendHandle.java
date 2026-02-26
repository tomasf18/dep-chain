package ist.depchain.network;

interface SendHandle {
    void cancel(); // handler for canceling the send retransmission task (used by StubbornLink)
}
