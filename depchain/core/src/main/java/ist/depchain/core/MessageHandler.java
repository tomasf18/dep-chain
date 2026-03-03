package ist.depchain.core;

import ist.depchain.common.ClientRequest;
import ist.depchain.common.ClientResponse;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.Block;
import com.google.protobuf.ByteString;
import java.util.Set;
import java.util.HashSet;

public class MessageHandler {
    private final ServerContext serverContext;
    private final BasicHotStuffProtocol protocol;
    private final HotStuffCoordinator coordinator;

    public MessageHandler(ServerContext serverContext, HotStuffCoordinator coordinator) {
        this.serverContext = serverContext;
        serverContext.getPerfectLink().registerReceiver(this::handleIncomingMessage);
        this.coordinator = coordinator;
        this.protocol = coordinator.getProtocol();
    }

    private void handleIncomingMessage(String sourceId, byte[] data) {
        // try parsing as ClientRequest first
        try {
            ClientRequest clientRequest = ClientRequest.parseFrom(data);
            handleClientRequest(sourceId, clientRequest);
            return;
        } catch (Exception e) {
            // not a ClientRequest, try next
        }

        // try parsing as HotStuffMessage
        try {
            HotStuffMessage hotstuffMsg = HotStuffMessage.parseFrom(data);
            handleHotStuffMessage(sourceId, hotstuffMsg);
            return;
        } catch (Exception e) {
            // not a HotStuffMessage either
        }

        System.err.println("Failed to parse message from " + sourceId + " as either ClientRequest, ClientResponse, or HotStuffMessage");

    }

    private void handleClientRequest(String sourceId, ClientRequest clientRequest) {
        System.out.println("Received ClientRequest from " + sourceId + " with requestId: " + clientRequest.getRequestId());
        
        ClientResponse response = ClientResponse.newBuilder()
            .setRequestId(clientRequest.getRequestId())
            .setCommitted(true)
            .setBlockId(ByteString.copyFrom("dummy-block-id".getBytes()))
            .build();
        serverContext.getPerfectLink().send(sourceId, response.toByteArray());

        Block block = Block.newBuilder()
            .setId(ByteString.copyFrom(("block-" + clientRequest.getRequestId()).getBytes()))
            .setCommand(clientRequest.getCommand())
            .build();
        
        HotStuffMessage hotstuffMsg = HotStuffMessage.newBuilder()
            .setType(HotStuffMessage.Type.PRE_COMMIT)
            .setViewNumber(0) // placeholder, HotStuffCoordinator will manage view numbers
            .setBlock(block)
            .build();
        
        Set<String> broadcastDestinations = new HashSet<>(serverContext.getConfig().getBlockChainServers().keySet());
        broadcastDestinations.remove(serverContext.getConfig().getSelfId()); // exclude self
        serverContext.getPerfectLink().broadcast(broadcastDestinations, hotstuffMsg.toByteArray());
    }

    private void handleHotStuffMessage(String sourceId, HotStuffMessage hotstuffMsg) {
        System.out.println("Received HotStuffMessage from " + sourceId + " with type: " + hotstuffMsg.getType());
        // TODO: Add actual handling logic for different HotStuff message types (e.g., Propose, Vote, etc.)
        coordinator.restartTimer();
        protocol.processMessage(sourceId, hotstuffMsg);
    }
}
