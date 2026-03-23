package ist.depchain.client;

import com.google.protobuf.ByteString;

import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    public void submitNativeTransfer(String toHex, BigInteger value, BigInteger gasPrice, BigInteger gasLimit, long nonce) {
        Address to = Address.fromHexString(toHex);

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
        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, appMsg.toByteArray());

        System.out.println("[SENT] tx reqId=" + reqId
                + " from=" + signedTx.getFrom()
                + " to=" + signedTx.getTo()
                + " value=" + signedTx.getValue()
                + " nonce=" + signedTx.getNonce());
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