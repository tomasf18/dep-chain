package ist.depchain.core.hotstuff;

import com.google.protobuf.ByteString;
import ist.depchain.common.Block;
import ist.depchain.common.Command;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.core.ServerContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BasicHotStuffCoordinator {
    private final ServerContext serverContext;
    private final BasicHotStuffUtils utils;
    private final BlockStorage storage;
    private final AtomicInteger currentView = new AtomicInteger(1);
    private int n, f;

    // Safety State
    private QC lockedQC = BasicHotStuffUtils.genesisQC;  // [PHASE 3] - The latest QC that was locked
    private QC prepareQC = BasicHotStuffUtils.genesisQC; // [PHASE 1] - The latest prepare QC

    // Phase Tracking
    private final Map<HotStuffMessage.Type, Map<ByteString, Set<HotStuffMessage>>> voteCollector = new ConcurrentHashMap<>();
    private final Map<Integer, Set<HotStuffMessage>> newViewMsgs = new ConcurrentHashMap<>();
    private final BasicHotStuffTree tree;

    private Timer viewTimer;
    private static final long TIMEOUT_MS = 1000000;

    public BasicHotStuffCoordinator(ServerContext serverContext) {
        this.serverContext = serverContext;
        this.n = serverContext.getConfig().getN();
        this.f = serverContext.getConfig().getF();
        this.storage = new BlockStorage();
        this.utils = new BasicHotStuffUtils(this.storage);
        this.tree = new BasicHotStuffTree();
        startTimer();
    }

    public void startTimer() {
        if (viewTimer != null) {viewTimer.cancel();}
        viewTimer = new Timer();
        viewTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("[TIMEOUT] - Starting new view");
                startNextView();
            }
        }, TIMEOUT_MS);
    }

    public void restartTimer() {
        startTimer();
    }

    public String getLeader(int view){
        List<String> servers = new ArrayList<>(serverContext.getConfig().getBlockChainServers().keySet());
        Collections.sort(servers);
        return servers.get(view % servers.size());
    }

    public boolean amILeaderOfView(int view){
        String id = serverContext.getConfig().getSelfId();
        return getLeader(view).equals(id);
    }

    /** PREPARE PHASE FOR LEADER **/
    public void onReceiveNewView(HotStuffMessage m){
        int view = m.getViewNumber();
        if(!amILeaderOfView(view)) {return;}
        newViewMsgs.computeIfAbsent(view, k -> new HashSet<>()).add(m);

        if(newViewMsgs.get(view).size() >= (n-f)){
            QC highQC = newViewMsgs.get(view).stream()
                    .map(HotStuffMessage::getJustify)
                    .max(Comparator.comparingInt(QC::getViewNumber))
                    .orElse(BasicHotStuffUtils.genesisQC);

            Command command = tree.getNextCommand();
            if(command == null)
                return;

            ByteString newId = ByteString.copyFromUtf8(UUID.randomUUID().toString());
            Block curProposal = utils.createLeaf(storage.getBlock(highQC.getBlockId()), command, newId);
            storage.putBlock(curProposal);

            HotStuffMessage prepareMsg = utils.msg(HotStuffMessage.Type.PREPARE, curProposal, highQC, currentView.get());

            broadcast(prepareMsg);

            newViewMsgs.remove(view);
        }
    }

    /** PRE-COMMIT PHASE FOR LEADER **/
    public void onReceivePrepareVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.PREPARE, currentView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PREPARE, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Set<HotStuffMessage> votes =  voteCollector.get(HotStuffMessage.Type.PREPARE)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && amILeaderOfView(vote.getViewNumber())){
            byte[] aggregatedSig = new byte[0]; //TODO - Replace with real crypto later
            this.prepareQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage preCommitMsg = utils.msg(HotStuffMessage.Type.PRE_COMMIT, null, prepareQC, currentView.get());
            broadcast(preCommitMsg);

            voteCollector.get(HotStuffMessage.Type.PREPARE).remove(blockId);
        }
    }

    /** COMMIT PHASE FOR LEADER **/
    public void onReceivePreCommitVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.PRE_COMMIT, currentView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PRE_COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.PRE_COMMIT)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && amILeaderOfView(vote.getViewNumber())){
            byte[] aggregatedSig = new byte[0]; //TODO - Replace with real crypto later
            QC preCommitQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage commitMsg = utils.msg(HotStuffMessage.Type.COMMIT, null, preCommitQC, currentView.get());
            broadcast(commitMsg);

            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** DECIDE PHASE FOR LEADER **/
    public void onReceiveCommitVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.COMMIT, currentView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();
        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.COMMIT)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && amILeaderOfView(vote.getViewNumber())){
            byte[] aggregatedSig = new byte[0]; // TODO - Replace with real crypto later
            QC commitQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage decideMSG = utils.msg(HotStuffMessage.Type.DECIDE, null, commitQC, currentView.get());
            broadcast(decideMSG);
            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** PREPARE PHASE FOR REPLICA **/
    public void onReceivePrepare(HotStuffMessage m){
        if(!utils.matchingMSG(m, HotStuffMessage.Type.PREPARE, currentView.get()))
            return;

        Block node = m.getBlock();
        QC justify = m.getJustify();

        boolean extendsJustify = utils.extendsFrom(node, justify.getBlockId());
        boolean safeNode = utils.safeNode(node, justify, lockedQC);

        if(extendsJustify && safeNode){
            storage.putBlock(node);

            sendVote(HotStuffMessage.Type.PREPARE, node, null);

            System.out.println("[REPLICA] - Voted PREPARE for Block: " + node.getId().toStringUtf8());
        }
    }

    /** PRE-COMMIT PHASE FOR REPLICA **/
    public void onReceivePreCommit(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PREPARE, currentView.get()))
            return;

        this.prepareQC = m.getJustify();
        Block node = storage.getBlock(prepareQC.getBlockId());
        sendVote(HotStuffMessage.Type.PRE_COMMIT, node, prepareQC);

        System.out.println("[REPLICA] - Voted PRE-COMMIT for view " + currentView.get());
    }

    /** COMMIT PHASE FOR REPLICA **/
    public void onReceiveCommit(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PRE_COMMIT, currentView.get()))
            return;

        this.lockedQC = m.getJustify();
        System.out.println("[REPLICA] - Locked QC for view " + currentView.get());

        Block node = storage.getBlock(lockedQC.getBlockId());
        sendVote(HotStuffMessage.Type.COMMIT, node, lockedQC);

        System.out.println("[REPLICA] - Voted COMMIT for view " + currentView.get());
    }

    /** DECIDE PHASE FOR REPLICA **/
    public void onReceiveDecide(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.COMMIT, currentView.get()))
            return;

        Block commitedBlock = storage.getBlock(m.getJustify().getBlockId());
        serverContext.getBlockChain().appendBlock(commitedBlock.getCommand().getData());

        startNextView();
    }

    /** HELPER FUNCTIONS **/
    // Method to start a new view
    public synchronized void startNextView(){
        int oldView = currentView.get();
        int newView = currentView.incrementAndGet();
        voteCollector.clear();

        HotStuffMessage newViewMSG = utils.msg(HotStuffMessage.Type.NEW_VIEW, null, prepareQC, oldView);
        String nextLeader = getLeader(newView);

        System.out.println("[VIEW CHANGE] - Moving to view " + newView + " with leader " + nextLeader);

        byte[] payload = newViewMSG.toByteArray();
        serverContext.getPerfectLink().send(nextLeader, payload);
    }

    // Hub of received messages
    public void processMessage(String sourceId, HotStuffMessage m){
        // Basic Validation - Is this a message for the current view
        if(m.getViewNumber() < currentView.get() && m.getType() != HotStuffMessage.Type.NEW_VIEW)
            return;

        boolean isVote = !m.getPartialSig().isEmpty();
        HotStuffMessage.Type msgType = m.getType();
        // ROUTE BY TYPE
        switch (msgType) {
            case NEW_VIEW:
                onReceiveNewView(m);
                break;
            case PREPARE:
                if(isVote) onReceivePrepareVote(sourceId, m);
                else onReceivePrepare(m);
                break;
            case PRE_COMMIT:
                if(isVote) onReceivePreCommitVote(sourceId, m);
                else onReceivePreCommit(m);
                break;
            case COMMIT:
                if(isVote) onReceiveCommitVote(sourceId, m);
                else onReceiveCommit(m);
                break;
            case DECIDE:
                if(!isVote) onReceiveDecide(m);
                break;
        }
    }

    // General send vote function for replicas
    private void sendVote(HotStuffMessage.Type msgType, Block node, QC justify){
        String leaderId = getLeader(currentView.get());

        byte[] partialSign = utils.getMsgDigest(msgType, currentView.get(), node.getId());
        HotStuffMessage vote = utils.voteMsg(msgType, node, justify, currentView.get(), partialSign);

        serverContext.getPerfectLink().send(leaderId, vote.toByteArray());
    }

    private void broadcast(HotStuffMessage m){
        Set<String> destinations = serverContext.getConfig().getBlockChainServers().keySet();
        serverContext.getPerfectLink().broadcast(destinations, m.toByteArray());
    }
}