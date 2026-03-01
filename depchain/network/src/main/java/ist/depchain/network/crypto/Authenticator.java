package ist.depchain.network.crypto;

import com.google.protobuf.ByteString;

import ist.depchain.common.Envelope;
import ist.depchain.common.utils.Config;
import ist.depchain.network.interfaces.MessageAuthenticator;

public class Authenticator implements MessageAuthenticator {

    private final Config config;

    public Authenticator(Config config) {
        this.config = config;
    }

    @Override
    public boolean shouldAuthenticate(String peerId) {
        return config.getBlockChainServers().containsKey(peerId); // only authenticate messages from other blockchain servers, not from clients
    }

    // builds envelope WITHOUT signature, serialize, signs the bytes, rebuild envelope WITH signature -> signature covers all fields
    @Override
    public Envelope signMessage(Envelope.Builder builder) {
        byte[] unsignedBytes = builder.build().toByteArray(); // sign over unsigned bytes
        byte[] signature = Crypto.sign(unsignedBytes, config.getSelfPrivateKeyPathString(), config.getSignatureAlgorithm());    
        return builder.setSignature(ByteString.copyFrom(signature)).build();
    }
    
    // re-builds the envelope without the signature field and verifies against those bytes
    @Override
    public boolean verifyMessage(Envelope envelope) {
        byte[] receivedSig = envelope.getSignature().toByteArray();
        if (receivedSig.length == 0) {
            System.err.println("PerfectLink: Missing signature from " + envelope.getSenderId());
            return false;
        }
        // strip signature to get the bytes that were originally signed
        Envelope unsigned = envelope.toBuilder().clearSignature().build();
        return Crypto.verify(unsigned.toByteArray(), receivedSig, config.getTrustedProcessKeyPathString(envelope.getSenderId()), config.getSignatureAlgorithm());
    }
    
}
