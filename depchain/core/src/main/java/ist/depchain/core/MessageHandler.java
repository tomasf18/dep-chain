package ist.depchain.core;

import ist.depchain.common.ClientRequest;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;
import ist.depchain.common.ApplicationMessage;

public class MessageHandler {
    private final ServerContext serverContext;
    private final BasicHotStuffCoordinator coordinator;

    public MessageHandler(ServerContext serverContext, BasicHotStuffCoordinator coordinator) {
        this.serverContext = serverContext;
        this.serverContext.getPerfectLink().registerReceiver(this::handleIncomingMessage);
        this.coordinator = coordinator;
    }

    private void handleIncomingMessage(String sourceId, byte[] data) {
        try {
            ApplicationMessage wrapper = ApplicationMessage.parseFrom(data);
            switch (wrapper.getContentCase()) {
                case CLIENT_REQUEST:
                    handleClientRequest(sourceId, wrapper.getClientRequest());
                    break;
                case HOTSTUFF_MESSAGE:
                    handleHotStuffMessage(sourceId, wrapper.getHotstuffMessage());
                    break;
                default:
                    System.err.println("Unknown message type from " + sourceId);
            }
        } catch (Exception e) {
            System.err.println("Failed to parse ApplicationMessage from " + sourceId);
        }
    }

    private void handleClientRequest(String sourceId, ClientRequest clientRequest) {
        System.out.println("[INFO] Received client request " + clientRequest.getRequestId() + " from " + sourceId);
        coordinator.enqueueClientRequest(clientRequest);
    }

    private void handleHotStuffMessage(String sourceId, HotStuffMessage hotstuffMsg) {
        System.out.println("Received HotStuffMessage from " + sourceId + " with type: " + hotstuffMsg.getType());
        coordinator.restartTimer();
        coordinator.processMessage(sourceId, hotstuffMsg);
    }
}
