package ist.depchain.client;

import ist.depchain.network.abstractions.AuthenticatedPerfectLink;
import ist.depchain.network.abstractions.StubbornLink;
import ist.depchain.network.abstractions.UdpFairLossLink;

/* protobuf classes */
import ist.depchain.common.utils.Config;
import ist.depchain.network.crypto.Authenticator;
import ist.depchain.network.crypto.KeyLoader;
import ist.depchain.common.utils.AddressUtils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.hyperledger.besu.datatypes.Address;

public class ClientContext {
    private final Config config;
    private final Address selfAddress;
    // Nonce managed client-side; only incremented after a successful commit
    private final AtomicLong nonce = new AtomicLong(0);
    
    private final UdpFairLossLink fairLossLink;
    private final StubbornLink stubbornLink;
    private final AuthenticatedPerfectLink authenticatedPerfectLink;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private final AtomicInteger requestId = new AtomicInteger(0);

    // requestId -> original request data (for logging committed transactions -> debugging)
    private final Map<Integer, String> requestDataMap = new ConcurrentHashMap<>();
    private final List<String> commitedLog = Collections.synchronizedList(new ArrayList<>());

    public ClientContext(Config config) {
        this.config = config;
        fairLossLink = new UdpFairLossLink(config);
        stubbornLink = new StubbornLink(config, fairLossLink);
        authenticatedPerfectLink = new AuthenticatedPerfectLink(config, stubbornLink, fairLossLink, new Authenticator(config));
        this.privateKey = KeyLoader.loadPrivateKey(config.getSelfPrivateKeyPathString());
        if (this.privateKey == null) {
            throw new RuntimeException("Failed to load private key from " + config.getSelfPrivateKeyPathString());
        }
        this.publicKey = KeyLoader.loadPublicKey(config.getSelfPublicKeyPathString());
        if (this.publicKey == null) {
            throw new RuntimeException("Failed to load public key for " + config.getSelfId());
        }
        this.selfAddress = AddressUtils.deriveAddress(publicKey);
    }
    
    public void start() {
        authenticatedPerfectLink.start();
    }

    /** Returns the current nonce without advancing it. */
    public long getNonce() {
        return nonce.get();
    }

    /** Advances the nonce by one. Called only after a confirmed commit. */
    public void incrementNonce() {
        nonce.incrementAndGet();
    }

    public void stop() {
        authenticatedPerfectLink.stop();
    }

    /* Getters */
    
    public Config getConfig() {
        return config;
    }

    public AuthenticatedPerfectLink getAuthenticatedPerfectLink() {
        return authenticatedPerfectLink;
    }

    public AtomicInteger getRequestId() {
        return requestId;
    }

    public void registerRequestInMap(int requestId, String requestData) {
        requestDataMap.put(requestId, requestData);
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public Address getSelfAddress() {
        return selfAddress;
    }

    public Map<Integer, String> getRequestDataMap() {
        return requestDataMap;
    }

    public List<String> getCommitedLog() {
        return commitedLog;
    }

    public void setNonce(long nonce) {
        this.nonce.set(nonce);
    }

    public void setRequestId(int requestId) {
        this.requestId.set(requestId);
    }
}
