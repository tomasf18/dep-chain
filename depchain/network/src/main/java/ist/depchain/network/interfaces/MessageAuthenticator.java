package ist.depchain.network.interfaces;

import ist.depchain.common.Envelope;

public interface MessageAuthenticator {
    boolean shouldAuthenticate(String peerId);
    Envelope encrypt(String destinationId, Envelope.Builder builder);
    Envelope decrypt(Envelope envelope);
}