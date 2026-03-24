package ist.depchain.client;

import com.google.protobuf.ByteString;

import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.hyperledger.besu.datatypes.Address;

import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.utils.TransactionSigner;

public class ClientLibrary {
    private final ClientContext clientContext;
    private final MessageHandler messageHandler;

    public ClientLibrary(ClientContext clientContext, MessageHandler messageHandler) {
        this.clientContext = clientContext;
        this.messageHandler = messageHandler;

    }

    // append() and showLog() are placeholders to keep compatibility with stage 1 tests for now
    public void append(String data) {
    }

    public void showLog() {
    }

    private static final long SUBMIT_TIMEOUT_SECONDS = 60;

    /**
     * Signs and broadcasts a native DepCoin transfer, then blocks until the
     * transaction is committed (f+1 matching responses) or rejected by the
     * replicas (f+1 rejection responses).
     */
    public void submitNativeTransfer(String toHex, BigInteger value, BigInteger gasPrice, BigInteger gasLimit) {
        Address to = Address.fromHexString(toHex);
        long nonce = clientContext.getNonce();

        Transaction unsignedTx = new Transaction(clientContext.getSelfAddress(), to, value, new byte[0], gasPrice, gasLimit, nonce, null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(), clientContext.getConfig().getSignatureAlgorithm());

        int reqId = clientContext.getRequestId().incrementAndGet();

        ClientRequest unsignedReq = ClientRequest.newBuilder()
                .setClientId(clientContext.getConfig().getSelfId())
                .setRequestId(reqId)
                .setTransaction(signedTx.toProto())
                .build();

        ClientRequest signedReq = signRequest(unsignedReq);

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(signedReq)
                .build();

        messageHandler.getPendingRequests().put(reqId, new ConcurrentHashMap<>());
        clientContext.registerRequestInMap(reqId, "tx:" + signedTx.getFrom() + ":" + toHex + ":" + value + ":" + nonce);

        CompletableFuture<Void> committed = messageHandler.registerFuture(reqId);

        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, appMsg.toByteArray());

        System.out.println("[SENT] tx reqId=" + reqId
                + " from=" + signedTx.getFrom()
                + " to=" + to
                + " value=" + value
                + " nonce=" + nonce);

        try {
            committed.get(SUBMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            clientContext.incrementNonce();
            System.out.println("[COMMITTED] tx reqId=" + reqId + " nonce=" + nonce);
        } catch (TimeoutException e) {
            throw new RuntimeException(
                    "Transaction timed out waiting for commit (reqId=" + reqId + ", nonce=" + nonce + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted waiting for commit (reqId=" + reqId + ")", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause().getMessage(), e.getCause());
        }
    }

    private ClientRequest signRequest(ClientRequest unsigned) {
        try {
            byte[] sig = Crypto.sign(
                    unsigned.toBuilder().clearSignature().build().toByteArray(),
                    clientContext.getPrivateKey(),
                    clientContext.getConfig().getSignatureAlgorithm());

            return ClientRequest.newBuilder(unsigned)
                    .setSignature(ByteString.copyFrom(sig))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign client request", e);
        }
    }
}