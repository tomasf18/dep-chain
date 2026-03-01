package ist.depchain.client;

import java.util.Set;

import ist.depchain.common.ClientRequest;
import ist.depchain.common.Command;

public class ClientLibrary {
    private ClientContext clientContext;

    public ClientLibrary(ClientContext clientContext) {
        this.clientContext = clientContext;
    }

    public void append(String data){
        String commandType = "append";
        int reqId = clientContext.getRequestId().incrementAndGet();

        Command command = Command.newBuilder()
                .setType(commandType)
                .setData(data)
                .build();

        ClientRequest clientRequest = ClientRequest.newBuilder()
                                            .setClientId(clientContext.getConfig().getSelfId())
                                            .setRequestId(reqId)
                                            .setCommand(command)
                                            .build();

        System.out.println("[SENT] Client " + clientContext.getConfig().getSelfId() + " | Request Id: " + reqId + " | Data: " + data);

        byte[] payload = clientRequest.toByteArray();
        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getPendingRequests().put(reqId, 0); // initialize ack count
        clientContext.getPerfectLink().broadcast(destinations, payload);
    }
}
