package ist.depchain.client;

import com.google.protobuf.ByteString;

import java.util.Set;
import java.util.HashMap;

import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.Command;
import ist.depchain.network.crypto.Crypto;

public class ClientLibrary {
    private ClientContext clientContext;

    public ClientLibrary(ClientContext clientContext) {
        this.clientContext = clientContext;
    }

    public void append(String data){
        String commandType = "append";
        int reqId = clientContext.getRequestId().incrementAndGet();
        clientContext.registerRequestInMap(reqId, data);

        Command command = Command.newBuilder()
                .setType(commandType)
                .setData(data)
                .build();

        ClientRequest clientRequest = signRequest(ClientRequest.newBuilder()
                .setClientId(clientContext.getConfig().getSelfId())
                .setRequestId(reqId)
                .setCommand(command)
                .build());

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                                        .setClientRequest(clientRequest)
                                        .build();

        System.out.println("[SENT] Client " + clientContext.getConfig().getSelfId() + " | Request Id: " + reqId + " | Data: " + data);

        byte[] payload = appMsg.toByteArray();
        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getPendingRequests().put(reqId, new HashMap<>());
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, payload);
    }

    public void showLog() {
        String commandType = "show_log";
        int reqId = clientContext.getRequestId().incrementAndGet();

        Command command = Command.newBuilder()
                .setType(commandType)
                .build();

        ClientRequest clientRequest = signRequest(ClientRequest.newBuilder()
                .setClientId(clientContext.getConfig().getSelfId())
                .setRequestId(reqId)
                .setCommand(command)
                .build());

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                                        .setClientRequest(clientRequest)
                                        .build();

        System.out.println("[SENT] Client " + clientContext.getConfig().getSelfId() + " | Request Id: " + reqId + " | Show log");

        byte[] payload = appMsg.toByteArray();
        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getPendingRequests().put(reqId, new HashMap<>());
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, payload);
    }

    private ClientRequest signRequest(ClientRequest unsigned) {
        try {
            byte[] sig = Crypto.sign(
                    unsigned.toByteArray(),
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
