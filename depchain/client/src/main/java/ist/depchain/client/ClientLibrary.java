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
import ist.depchain.network.crypto.Crypto;

public class ClientLibrary {
    private final ClientContext clientContext;

    public ClientLibrary(ClientContext clientContext) {
        this.clientContext = clientContext;
    }

    public void append(String data) {
    }

    public void showLog() {
    }

    private static final long SUBMIT_TIMEOUT_SECONDS = 60;

    /**
     * Signs and broadcasts a native DepCoin transfer, then blocks until the
     * transaction is committed (f+1 matching responses) or rejected by the
     * replicas (f+1 rejection responses).
     *
     * <p>The nonce is managed internally: it is read before the call and
     * incremented only after a confirmed commit. On rejection or timeout a
     * {@link RuntimeException} is thrown and the nonce is left unchanged so
     * the caller can retry or inspect the error.
     *
     * @throws RuntimeException if the transaction is rejected by the replicas
     *                          or no commit is confirmed within the timeout
     */
    public void submitNativeTransfer(String toHex, BigInteger value, BigInteger gasPrice, BigInteger gasLimit) {
        Address to = Address.fromHexString(toHex);
        long nonce = clientContext.peekNonce();

        Transaction unsignedTx = new Transaction(
                clientContext.getSelfAddress(),
                to,
                value,
                new byte[0],
                gasPrice,
                gasLimit,
                nonce,
                null
        );

        Transaction signedTx = TransactionSigner.sign(
                unsignedTx,
                clientContext.getPrivateKey(),
                clientContext.getConfig().getSignatureAlgorithm()
        );

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

        clientContext.getPendingRequests().put(reqId, new ConcurrentHashMap<>());
        clientContext.registerRequestInMap(reqId,
                "tx:" + signedTx.getFrom() + ":" + toHex + ":" + value + ":" + nonce);

        CompletableFuture<Void> committed = clientContext.registerFuture(reqId);

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
            // future.completeExceptionally() was called - rejection from replicas
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