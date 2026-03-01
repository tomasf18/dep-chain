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
        int reqId = clientContext.getRequestId().incrementAndGet();

        Command cmd = Command.newBuilder()
                .setType("append")
                .setData(data)
                .build();

        ClientRequest cltReq = ClientRequest.newBuilder()
                                            .setClientId(clientContext.getConfig().getSelfId())
                                            .setRequestId(reqId)
                                            .setCommand(cmd)
                                            .build();

        System.out.println("[SENT] Client " + clientContext.getConfig().getSelfId() + " Request Id: " + reqId + " Data: " + data);

        byte[] payload = cltReq.toByteArray();
        Set<String> destinations = clientContext.getConfig().getProcesses().keySet();
        destinations.remove(clientContext.getConfig().getSelfId()); // don't send to self
        clientContext.getPendingRequests().put(reqId, 0); // initialize ack count
        clientContext.getPerfectLink().broadcast(destinations.stream().toList(), payload);
    }
}
