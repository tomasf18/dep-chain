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

    private final Map<HotStuffMessage.Type, Map<ByteString, Set<HotStuffMessage>>> voteCollector = new ConcurrentHashMap<>();
    private final Map<Integer, Set<HotStuffMessage>> newViewMsgs = new ConcurrentHashMap<>();
    private final BasicHotStuffTree tree = new BasicHotStuffTree();
    private final CommandMempool mempool = new CommandMempool();

    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> viewTimerFuture;
    private static final long TIMEOUT_MS = 5000;

    private boolean quorumReady = false; 

    public BasicHotStuffCoordinator(ServerContext serverContext) {
        this.serverContext = serverContext;
        this.utils = new BasicHotStuffUtils(this.tree);
        this.n = serverContext.getConfig().getN();
        this.f = serverContext.getConfig().getF();
        this.hotStuffQuorum = (n - f);
        this.prepareQC = this.utils.getGenesisQC();
        this.lockedQC = this.utils.getGenesisQC();
        startTimer();
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
            while (true) {
                try {
                    serverContext.getPerfectLink().send(leader, payload);
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
                }
            }
        }).start();
    }

    private void proposalLoop() {
        while (true) {
            synchronized (this) {
                try {
                    while (!quorumReady || mempool.isEmpty()) {
                        wait(); // equivalent to proposalReady.await()
                    }
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
        if (m.getViewNumber() < currentView.get() && m.getType() != HotStuffMessage.Type.NEW_VIEW)
            return;

        boolean isVote = !m.getPartialSig().isEmpty();
        HotStuffMessage.Type msgType = m.getType();
        switch (msgType) {
            case NEW_VIEW:
                onReceiveNewView(m);
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
                break;
        }
    }

    private void startTimer() {
        restartTimer();
    }

    public synchronized void restartTimer() {
        if (viewTimerFuture != null) viewTimerFuture.cancel(false);
        viewTimerFuture = timerExecutor.schedule(() -> {
            System.out.println("[TIMEOUT] - Starting new view");
            startNextView();
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
    public void onReceiveNewView(HotStuffMessage m) {
        if (!amILeaderOfView(currentView.get())) return;
        if (!utils.matchingMSG(m, HotStuffMessage.Type.NEW_VIEW, currentView.get() - 1)) return;

        synchronized (this) {
            int view = m.getViewNumber();
            newViewMsgs.computeIfAbsent(view, k -> new HashSet<>()).add(m);

            if (reachedQuorum(newViewMsgs.get(view).size())) {
                quorumReady = true;
                notify();
            }
        }
    }

    private void doPropose() {
        int oldView = currentView.get() - 1;
        Set<HotStuffMessage> msgs = newViewMsgs.get(oldView);
        
        // compute highQC from accumulated new-view messages
        QC highQC = utils.getGenesisQC();
        for (HotStuffMessage m : msgs) {
            if (!utils.verifyQC(m.getJustify())) {
                continue;
            }
            if (m.getJustify().getViewNumber() > highQC.getViewNumber()) {
                highQC = m.getJustify();
            }
        }

        ClientRequest clientRequest = mempool.dequeue(); // guaranteed non-null here
        Command command = clientRequest.getCommand().toBuilder()
            .setClientId(clientRequest.getClientId())
            .setRequestId(clientRequest.getRequestId())
            .build();

        Block curProposal = utils.createLeaf(tree.getBlock(highQC.getBlockId()), command);
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

        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.PREPARE).computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if (amILeaderOfView(vote.getViewNumber()) && reachedQuorum(votes.size())) {
            this.prepareQC = utils.createQC(votes);

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

        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.PRE_COMMIT).computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if (amILeaderOfView(vote.getViewNumber()) && reachedQuorum(votes.size())) {
            QC preCommitQC = utils.createQC(votes);

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
        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.COMMIT).computeIfAbsent(blockId,
                k -> new HashSet<>());
        votes.add(vote);

        if (amILeaderOfView(vote.getViewNumber()) && reachedQuorum(votes.size())) {
            QC commitQC = utils.createQC(votes);

            HotStuffMessage decideMsg = utils.msg(HotStuffMessage.Type.DECIDE, null, commitQC, currentView.get());
            broadcast(decideMsg);
            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** PREPARE phase as Replica **/
    public void onReceivePrepare(String sourceId, HotStuffMessage m) {
        if (!utils.matchingMSG(m, HotStuffMessage.Type.PREPARE, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        Block node = m.getBlock();
        QC justify = m.getJustify();

        if (!utils.verifyQC(justify)) {
            System.out.println("[REPLICA] - Received PREPARE with invalid QC from " + sourceId);
            return;
        }

        boolean extendsJustify = utils.extendsFrom(node, justify.getBlockId());
        boolean safeNode = utils.safeNode(node, justify, lockedQC);

        if (extendsJustify && safeNode) {
            tree.putBlock(node);

            sendVote(HotStuffMessage.Type.PREPARE, node, null);

            System.out.println("[REPLICA] - Voted PREPARE for Block: " + node.getId().toStringUtf8());
        }
    }

    /** PRE-COMMIT phase as Replica **/
    public void onReceivePreCommit(String sourceId, HotStuffMessage m) {
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PREPARE, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        QC newPrepareQC = m.getJustify();
        if (!utils.verifyQC(newPrepareQC)) {
            System.out.println("[REPLICA] - Received PRE-COMMIT with invalid QC from " + sourceId);
            return;
        }
        this.prepareQC = newPrepareQC; 
        
        Block node = tree.getBlock(prepareQC.getBlockId());
        sendVote(HotStuffMessage.Type.PRE_COMMIT, node, prepareQC);

        System.out.println("[REPLICA] - Voted PRE-COMMIT for view " + currentView.get());
    }

    /** COMMIT phase as Replica **/
    public void onReceiveCommit(String sourceId, HotStuffMessage m) {
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PRE_COMMIT, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        QC newLockedQC = m.getJustify();
        if (!utils.verifyQC(newLockedQC)) {
            System.out.println("[REPLICA] - Received COMMIT with invalid QC from " + sourceId);
            return;
        }
        this.lockedQC = newLockedQC; 

        System.out.println("[REPLICA] - Locked QC for view " + currentView.get());

        Block node = tree.getBlock(lockedQC.getBlockId());
        sendVote(HotStuffMessage.Type.COMMIT, node, lockedQC);

        System.out.println("[REPLICA] - Voted COMMIT for view " + currentView.get());
    }

    /** DECIDE phase as Replica **/
    public void onReceiveDecide(String sourceId, HotStuffMessage m) {
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.COMMIT, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        if (!utils.verifyQC(m.getJustify())) {
            System.out.println("[REPLICA] - Received DECIDE with invalid QC from " + sourceId);
            return;
        }

        Block commitedBlock = tree.getBlock(m.getJustify().getBlockId());

        // upcall to the server to execute the command and respond to clients
        serverContext.getBlockChain().appendBlock(commitedBlock.getCommand().getData());

        
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
        int oldView = currentView.get();
        int newView = currentView.incrementAndGet();
        voteCollector.clear();
        quorumReady = false;

        HotStuffMessage newViewMsg = utils.msg(HotStuffMessage.Type.NEW_VIEW, null, prepareQC, oldView);
        ApplicationMessage wrapper = ApplicationMessage.newBuilder()
            .setHotstuffMessage(newViewMsg)
            .build();
        String nextLeader = getLeaderForView(newView);

        System.out.println("[VIEW CHANGE] - Moving to view " + newView + " with leader " + nextLeader);

        serverContext.getPerfectLink().send(nextLeader, wrapper.toByteArray());
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