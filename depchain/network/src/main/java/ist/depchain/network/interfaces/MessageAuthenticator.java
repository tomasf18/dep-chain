package ist.depchain.network.interfaces;

import ist.depchain.common.Envelope;

public interface MessageAuthenticator {
    boolean shouldAuthenticate(String peerId);
    Envelope signMessage(Envelope.Builder builder);
    boolean verifyMessage(Envelope envelope);
}