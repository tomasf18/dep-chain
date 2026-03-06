package ist.depchain.core.hotstuff;

import com.google.protobuf.ByteString;

import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.Block;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.ClientResponse;
import ist.depchain.common.Command;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.core.ServerContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BasicHotStuffCoordinator {
    private final ServerContext serverContext;
    private final BasicHotStuffUtils utils;
    private final AtomicInteger currentView = new AtomicInteger(1); // incremented either by finishing a decision or by a NEXT_VIEW interrupt
    private int n, f;
    private final int hotStuffQuorum; // n - f, the number of votes needed to form a QC

    private QC lockedQC; // [PHASE 3] - The latest QC that was locked (highest QC - a.k.a. lockedQC.viewNumber - for which the replica voted COMMIT)
    private QC prepareQC; // [PHASE 1] - The latest prepare QC (highest QC for which the replica voted PRE-COMMIT)

    private final Map<HotStuffMessage.Type, Map<ByteString, Map<String, HotStuffMessage>>> voteCollector = new ConcurrentHashMap<>(); // phase -> blockId -> set of votes (maps from voterId to vote message)
    private final Map<Integer, Map<String, HotStuffMessage>> newViewMsgs = new ConcurrentHashMap<>();
    private final BasicHotStuffTree tree = new BasicHotStuffTree();
    private final CommandMempool mempool = new CommandMempool();

    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> viewTimerFuture;
    private static final long INITIAL_TIMEOUT_MS = 10000; // 10 seconds
    private static final long MAX_TIMEOUT_MS = 60000;
    private long currentTimeoutMs = INITIAL_TIMEOUT_MS;
    private int consecutiveTimeouts = 0;
    private static final int BACKOFF_THRESHOLD = 2; // start backoff after 2 consecutive failures


    private boolean quorumReady = false; 

    public BasicHotStuffCoordinator(ServerContext serverContext) {
        this.serverContext = serverContext;
        this.utils = new BasicHotStuffUtils(this.tree);
        this.n = serverContext.getConfig().getN();
        this.f = serverContext.getConfig().getF();
        this.hotStuffQuorum = (n - f);
        this.prepareQC = this.utils.getGenesisQC();
        this.lockedQC = this.utils.getGenesisQC();
    }

    public void start() {
        new Thread(this::proposalLoop, "hotstuff-proposer").start(); // async proposal 

        HotStuffMessage newViewMsg = utils.msg(HotStuffMessage.Type.NEW_VIEW, null, prepareQC, 0);
        ApplicationMessage wrapper = ApplicationMessage.newBuilder()
                                        .setHotstuffMessage(newViewMsg)
                                        .build();
        String leader = getLeaderForView(1);
        byte[] payload = wrapper.toByteArray();

        // retry until session key is established (handshake may not be done yet)
        new Thread(() -> {
            try {
                serverContext.getPerfectLink().getAuthenticator().waitForHandshakesComplete();
                serverContext.getPerfectLink().send(leader, payload);
                startTimer();
            } catch (Exception e) {
                System.err.println("[COORDINATOR | ERROR] - Failed to send initial NEW_VIEW message to " + leader);
            }
            
        }).start();
    }

    private void proposalLoop() {
        while (true) {
            synchronized (this) {
                try {
                    while (!quorumReady || mempool.isEmpty()) {
                        if (quorumReady) {
                            System.out.println("[COORDINATOR | LEADER] - Quorum ready but waiting for client request to propose for view " + currentView.get());
                        } else if (!mempool.isEmpty()) {
                            System.out.println("[COORDINATOR | LEADER] - Client request available but waiting for quorum to be ready for view " + currentView.get());
                        }
                        wait(); 
                    }
                    System.out.println("[COORDINATOR | LEADER] - Quorum ready and client request available, proposing new block for view " + currentView.get());
                    doPropose();
                    quorumReady = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public synchronized void processMessage(String sourceId, HotStuffMessage m) {
        // Basic Validation - Is this a message for the current view
        System.out.println("[COORDINATOR | INFO] - Received message of type " + m.getType() + " from " + sourceId + " for view " + m.getViewNumber());
        
        // only restart the timer for messages that represent progress in the current view, future-view messages are buffered but should noti delay our own timeout
        boolean isCurrentView = (m.getViewNumber() == currentView.get());
        boolean isCurrentNewView = (m.getType() == HotStuffMessage.Type.NEW_VIEW && m.getViewNumber() == currentView.get() - 1);
        if (isCurrentView || isCurrentNewView) {
            restartTimer();
        }

        if (m.getViewNumber() < currentView.get() && m.getType() != HotStuffMessage.Type.NEW_VIEW) {
            System.out.println("[COORDINATOR | INFO] - Ignoring old message from " + sourceId + " for view " + m.getViewNumber() + ", current view is " + currentView.get());
            return;
        }

        boolean isVote = !m.getPartialSig().isEmpty();
        HotStuffMessage.Type msgType = m.getType();
        switch (msgType) {
            case NEW_VIEW:
                onReceiveNewView(sourceId,m);
                break;
            case PREPARE:
                if (isVote)
                    onReceivePrepareVote(sourceId, m);
                else
                    onReceivePrepare(sourceId, m);
                break;
            case PRE_COMMIT:
                if (isVote)
                    onReceivePreCommitVote(sourceId, m);
                else
                    onReceivePreCommit(sourceId, m);
                break;
            case COMMIT:
                if (isVote)
                    onReceiveCommitVote(sourceId, m);
                else
                    onReceiveCommit(sourceId, m);
                break;
            case DECIDE:
                if (!isVote)
                    onReceiveDecide(sourceId, m);
                break;
            default:
                System.err.println("[COORDINATOR | ERROR] - Unknown message type from " + sourceId);
                break;
        }
    }

    private void startTimer() {
        restartTimer();
    }

    public synchronized void restartTimer() {
        if (viewTimerFuture != null) viewTimerFuture.cancel(false);
        viewTimerFuture = timerExecutor.schedule(() -> {
            System.out.println("[COORDINATOR | TIMEOUT] - Starting new view");
            startNextView();
        }, currentTimeoutMs, TimeUnit.MILLISECONDS);
    }

    public String getLeaderForView(int view) {
        List<String> servers = new ArrayList<>(serverContext.getConfig().getBlockChainServers().keySet());
        Collections.sort(servers);
        return servers.get(view % servers.size());
    }

    public boolean amILeaderOfView(int view) {
        String id = serverContext.getConfig().getSelfId();
        return getLeaderForView(view).equals(id);
    }

    /** PREPARE phase as Leader **/
    public void onReceiveNewView(String sourceId, HotStuffMessage m) {
        int targetView = m.getViewNumber() + 1; // the view this NEW_VIEW is intended for

        // check we're the leader of the view these messages are building toward, not necessarily the current view as messages may arrive early
        if (!amILeaderOfView(targetView)) return;

        synchronized (this) {
            int view = m.getViewNumber();
            newViewMsgs.computeIfAbsent(view, k -> new HashMap<>()).put(sourceId, m);

            int count = newViewMsgs.get(view).size();
            System.out.println("[COORDINATOR | LEADER] - Received " + count + "/" + hotStuffQuorum + " NEW_VIEW messages for view " + view + " (targeting view " + targetView + ")");

            // but only signal the proposalLoop if we've actually advanced to targetView already; if not, the messages are buffered, startNextView() checks them on arrival
            if (reachedQuorum(count) && currentView.get() == targetView) {
                System.out.println("[COORDINATOR | LEADER] - Quorum reached for view " + targetView + ", ready to propose");
                quorumReady = true;
                notify();
            }
        }
    }

    private void doPropose() {
        int oldView = currentView.get() - 1;
        Map<String, HotStuffMessage> msgs = newViewMsgs.get(oldView);

        QC highQC = utils.getGenesisQC();
        for (HotStuffMessage m : msgs.values()) { 
            if (!utils.verifyQC(m.getJustify())) continue;
            if (m.getJustify().getViewNumber() > highQC.getViewNumber()) {
                highQC = m.getJustify();
            }
        }

        // get the parent block from the highQC, defaulting to genesis if not found
        Block parent = tree.getBlock(highQC.getBlockId());
        if (parent == null) {
            // if the block isn't in our tree, use genesis block (can happen if the highQC is from a view we were not part of, and thus we don't have the blocks for it; but it's still safe to build on top of genesis, since any QC extends from genesis)
            // e.g., if there is much latency and we receive NEW_VIEW messages for view 5 while we're still on view 1, the highQC in those messages may be from view 4, and we won't have the corresponding block in our tree
            parent = tree.getGenesisBlock();
            System.out.println("[COORDINATOR | LEADER] - Block for highQC not found in tree, using genesis as parent");
        }

        ClientRequest clientRequest = mempool.dequeue(); // guaranteed non-null 
        Command command = clientRequest.getCommand().toBuilder()
            .setClientId(clientRequest.getClientId())
            .setRequestId(clientRequest.getRequestId())
            .build();

        Block curProposal = utils.createLeaf(parent, command);
        tree.putBlock(curProposal);

        HotStuffMessage prepareMsg = utils.msg(HotStuffMessage.Type.PREPARE, curProposal, highQC, currentView.get());
        broadcast(prepareMsg);

        newViewMsgs.remove(oldView); 
    }

    /** PRE-COMMIT phase as Leader **/
    public void onReceivePrepareVote(String sourceId, HotStuffMessage vote) {
        if (!utils.matchingMSG(vote, HotStuffMessage.Type.PREPARE, currentView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PREPARE, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Map<String, HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.PREPARE).computeIfAbsent(blockId, k -> new HashMap<>());
        votes.put(sourceId, vote);

        System.out.println("[COORDINATOR | LEADER] - Received PREPARE vote from " + sourceId + " for block " + blockId.toStringUtf8() + " in view " + vote.getViewNumber() + ". Total votes for this block: " + votes.size() + "/" + hotStuffQuorum);
        if (amILeaderOfView(vote.getViewNumber()) && reachedQuorum(votes.size())) {
            this.prepareQC = utils.createQC(votes.values());

            HotStuffMessage preCommitMsg = utils.msg(HotStuffMessage.Type.PRE_COMMIT, null, prepareQC,
                    currentView.get());
            broadcast(preCommitMsg);

            voteCollector.get(HotStuffMessage.Type.PREPARE).remove(blockId);
        }
    }

    /** COMMIT phase as Leader **/
    public void onReceivePreCommitVote(String sourceId, HotStuffMessage vote) {
        if (!utils.matchingMSG(vote, HotStuffMessage.Type.PRE_COMMIT, currentView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PRE_COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Map<String, HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.PRE_COMMIT).computeIfAbsent(blockId, k -> new HashMap<>());
        votes.put(sourceId, vote);

        if (amILeaderOfView(vote.getViewNumber()) && reachedQuorum(votes.size())) {
            QC preCommitQC = utils.createQC(votes.values());

            HotStuffMessage commitMsg = utils.msg(HotStuffMessage.Type.COMMIT, null, preCommitQC, currentView.get());
            broadcast(commitMsg);

            voteCollector.get(HotStuffMessage.Type.PRE_COMMIT).remove(blockId);
        }
    }

    /** DECIDE phase as Leader **/
    public void onReceiveCommitVote(String sourceId, HotStuffMessage vote) {
        if (!utils.matchingMSG(vote, HotStuffMessage.Type.COMMIT, currentView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();
        Map<String, HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.COMMIT).computeIfAbsent(blockId, k -> new HashMap<>());
        votes.put(sourceId, vote);

        if (amILeaderOfView(vote.getViewNumber()) && reachedQuorum(votes.size())) {
            QC commitQC = utils.createQC(votes.values());

            HotStuffMessage decideMsg = utils.msg(HotStuffMessage.Type.DECIDE, null, commitQC, currentView.get());
            broadcast(decideMsg);
            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** PREPARE phase as Replica **/
    public void onReceivePrepare(String sourceId, HotStuffMessage m) {
        System.out.println("[COORDINATOR | REPLICA] - Receivsssssssssssssed PREPARE from " + sourceId + " for view " + m.getViewNumber());
        if (!utils.matchingMSG(m, HotStuffMessage.Type.PREPARE, currentView.get()) || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        System.out.println("[COORDINATOR | REPLICA] - PREPARE message is for current view and from correct leader, processing...");

        Block node = m.getBlock();
        QC justify = m.getJustify();

        if (!utils.verifyQC(justify)) {
            System.out.println("[COORDINATOR | REPLICA] - Received PREPARE with invalid QC from " + sourceId);
            return;
        }

        boolean extendsJustify = utils.extendsFrom(node, justify.getBlockId());
        boolean safeNode = utils.safeNode(node, justify, lockedQC);

        if (extendsJustify && safeNode) {
            tree.putBlock(node);

            sendVote(HotStuffMessage.Type.PREPARE, node, null);

            System.out.println("[COORDINATOR | REPLICA] - Voted PREPARE for Block: " + node.getId().toStringUtf8());
        } else {
            System.out.println("[COORDINATOR | REPLICA] - Received PREPARE that does not extend justify or is not safe, ignoring. extendsJustify: " + extendsJustify + ", safeNode: " + safeNode);
        }
    }

    /** PRE-COMMIT phase as Replica **/
    public void onReceivePreCommit(String sourceId, HotStuffMessage m) {
        System.out.println("[COORDINATOR | REPLICA] - Received PRE-COMMIT from " + sourceId + " for view " + m.getViewNumber());
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PREPARE, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        QC newPrepareQC = m.getJustify();
        if (!utils.verifyQC(newPrepareQC)) {
            System.out.println("[COORDINATOR | REPLICA] - Received PRE-COMMIT with invalid QC from " + sourceId);
            return;
        }
        this.prepareQC = newPrepareQC; 
        
        Block node = tree.getBlock(prepareQC.getBlockId());
        sendVote(HotStuffMessage.Type.PRE_COMMIT, node, prepareQC);

        System.out.println("[COORDINATOR | REPLICA] - Voted PRE-COMMIT for view " + currentView.get());
    }

    /** COMMIT phase as Replica **/
    public void onReceiveCommit(String sourceId, HotStuffMessage m) {
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PRE_COMMIT, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        QC newLockedQC = m.getJustify();
        if (!utils.verifyQC(newLockedQC)) {
            System.out.println("[COORDINATOR | REPLICA] - Received COMMIT with invalid QC from " + sourceId);
            return;
        }
        this.lockedQC = newLockedQC; 

        System.out.println("[COORDINATOR | REPLICA] - Locked QC for view " + currentView.get());

        Block node = tree.getBlock(lockedQC.getBlockId());
        sendVote(HotStuffMessage.Type.COMMIT, node, lockedQC);

        System.out.println("[COORDINATOR | REPLICA] - Voted COMMIT for view " + currentView.get());
    }

    /** DECIDE phase as Replica **/
    public void onReceiveDecide(String sourceId, HotStuffMessage m) {
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.COMMIT, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        if (!utils.verifyQC(m.getJustify())) {
            System.out.println("[COORDINATOR | REPLICA] - Received DECIDE with invalid QC from " + sourceId);
            return;
        }

        Block commitedBlock = tree.getBlock(m.getJustify().getBlockId());

        // upcall to the server to execute the command and respond to clients
        serverContext.getCommandExecutor().executeCommand(commitedBlock.getCommand().getType(), commitedBlock.getCommand().getData());
        onCommit();
        
        Command command = commitedBlock.getCommand();
        synchronized (this) {
            mempool.discardIfPresent(command.getClientId(), command.getRequestId());
        }
        if (!command.getClientId().isEmpty()) {
            ClientResponse response = ClientResponse.newBuilder()
                    .setClientId(command.getClientId())
                    .setRequestId(command.getRequestId())
                    .setCommitted(true)
                    .setBlockId(commitedBlock.getId())
                    .build();
            serverContext.getPerfectLink().send(command.getClientId(), response.toByteArray());
        }

        startNextView();
    }

    /** HELPER FUNCTIONS **/
    // Method to start a new view
    public synchronized void startNextView() {
        consecutiveTimeouts++;
        if (consecutiveTimeouts > BACKOFF_THRESHOLD) {
            currentTimeoutMs = Math.min(currentTimeoutMs * 2, MAX_TIMEOUT_MS);
        }

        int oldView = currentView.get();
        int newView = currentView.incrementAndGet();
        voteCollector.clear();
        quorumReady = false;

        HotStuffMessage newViewMsg = utils.msg(HotStuffMessage.Type.NEW_VIEW, null, prepareQC, oldView);
        ApplicationMessage wrapper = ApplicationMessage.newBuilder()
            .setHotstuffMessage(newViewMsg)
            .build();
        String nextLeader = getLeaderForView(newView);

        System.out.println("[COORDINATOR | REPLICA] - Moving to view " + newView + " with leader " + nextLeader);
        serverContext.getPerfectLink().send(nextLeader, wrapper.toByteArray());

        // if we are the leader of the new view, check if messages arrived early and were buffered before we advanced
        if (amILeaderOfView(newView)) {
            Map<String, HotStuffMessage> buffered = newViewMsgs.get(oldView);
            if (buffered != null && reachedQuorum(buffered.size())) {
                System.out.println("[COORDINATOR | LEADER] - Found buffered quorum on view advance to " + newView);
                quorumReady = true;
                notify();
            }
        }

        restartTimer();
    }

    private void onCommit() {
        currentTimeoutMs = INITIAL_TIMEOUT_MS;
        consecutiveTimeouts = 0; // reset timeout counter on commit
    }

    // Check if a quorum of votes has been reached for a given message type and block ID
    private boolean reachedQuorum(int numberOfVotes) {
        return numberOfVotes >= hotStuffQuorum;
    }

    // Enqueue a client request for processing
    public synchronized void enqueueClientRequest(ClientRequest clientRequest) {
        mempool.enqueue(clientRequest);
        notify(); 
    }

    // General send vote function for replicas
    private void sendVote(HotStuffMessage.Type msgType, Block node, QC justify) {
        String leaderId = getLeaderForView(currentView.get());
        HotStuffMessage vote = utils.voteMsg(msgType, node, justify, currentView.get());
        ApplicationMessage wrapper = ApplicationMessage.newBuilder()
                                        .setHotstuffMessage(vote)
                                        .build();
        serverContext.getPerfectLink().send(leaderId, wrapper.toByteArray());
    }

    private void broadcast(HotStuffMessage m) {
        ApplicationMessage wrapper = ApplicationMessage.newBuilder()
                                        .setHotstuffMessage(m)
                                        .build();
        Set<String> destinations = serverContext.getConfig().getBlockChainServers().keySet();
        serverContext.getPerfectLink().broadcast(destinations, wrapper.toByteArray());
    }
}