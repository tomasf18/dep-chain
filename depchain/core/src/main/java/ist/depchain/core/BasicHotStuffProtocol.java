package ist.depchain.core;

import com.google.protobuf.ByteString;
import ist.depchain.common.*;
import ist.depchain.common.Block;
import ist.depchain.common.Command;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.core.hotstuff.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.weavechain.sig.ThresholdSigEd25519;
import com.weavechain.sig.ThresholdSigEd25519Params;
import com.weavechain.curve25519.EdwardsPoint;
import com.weavechain.curve25519.Scalar;

public class BasicHotStuffProtocol {
    private final BasicHotStuffUtils utils;
    private final BlockStorage storage;
    private final ServerContext serverContext;
    private final AtomicInteger curView = new AtomicInteger(1);
    private int n, f;

    // Safety State
    private QC lockedQC = BasicHotStuffUtils.genesisQC;  // [PHASE 3] - The latest QC that was locked
    private QC prepareQC = BasicHotStuffUtils.genesisQC; // [PHASE 1] - The latest prepare QC

    // Phase Tracking
    private final Map<HotStuffMessage.Type, Map<ByteString, Set<HotStuffMessage>>> voteCollector = new ConcurrentHashMap<>();
    private final Map<Integer, Set<HotStuffMessage>> newViewMsgs = new ConcurrentHashMap<>();
    private final Queue<Command> commandMempool = new LinkedList<>();

    // Threshold sign
    private final ThresholdSigEd25519 tsign;
    private final ThresholdSigEd25519Params tsignParams;
    private final Map<ByteString, List<EdwardsPoint>> riCollector =  new ConcurrentHashMap<>();

    public BasicHotStuffProtocol(ServerContext serverContext) {
        this.serverContext = serverContext;

        this.n = serverContext.getConfig().getN();
        this.f = serverContext.getConfig().getF();

        this.storage = new BlockStorage();
        this.utils = new BasicHotStuffUtils(this.storage);

        try {
            this.tsign = new ThresholdSigEd25519(this.f, this.n);
            this.tsignParams = this.tsign.generate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize HotStuff Cryptography: " + e.getMessage(), e);
        }
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

            Command command = commandMempool.poll();
            if(command == null)
                return;

            ByteString newId = ByteString.copyFromUtf8(UUID.randomUUID().toString());
            Block curProposal = utils.createLeaf(storage.getBlock(highQC.getBlockId()), command, newId);
            storage.putBlock(curProposal);

            HotStuffMessage prepareMsg = utils.msg(HotStuffMessage.Type.PREPARE, curView.get(), curProposal, highQC);
            broadcast(prepareMsg);

            newViewMsgs.remove(view);
        }
    }

    /** PREPARE PHASE FOR REPLICA **/
    public void onReceivePrepare(HotStuffMessage m){
        if(!utils.matchingMSG(m, HotStuffMessage.Type.PREPARE, curView.get()))
            return;

        Block node = m.getBlock();
        QC justify = m.getJustify();

        boolean extendsJustify = utils.extendsFrom(node, justify.getBlockId());
        boolean safeNode = utils.safeNode(node, justify, lockedQC);

        if(extendsJustify && safeNode){
            storage.putBlock(node);

            byte[] dummySig = new byte[0]; //TODO - Replace with real crypto later
            HotStuffMessage vote = utils.voteMsg(HotStuffMessage.Type.PREPARE, curView.get(), node, justify, dummySig);

            String leaderId = getLeader(curView.get());
            serverContext.getPerfectLink().send(leaderId, vote.toByteArray());

            System.out.println("[REPLICA] - Voted PREPARE for Block: " + node.getId().toStringUtf8());
        }
    }

    /** PRE-COMMIT PHASE FOR LEADER **/
    public void onReceivePrepareVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.PREPARE, curView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PREPARE, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Set<HotStuffMessage> votes =  voteCollector.get(HotStuffMessage.Type.PREPARE)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && amILeaderOfView(vote.getViewNumber())){
            byte[] aggregatedSig = new byte[0]; //TODO - Replace with real crypto later
            this.prepareQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage preCommitMsg = utils.msg(HotStuffMessage.Type.PRE_COMMIT, curView.get(), null, prepareQC);
            broadcast(preCommitMsg);

            voteCollector.get(HotStuffMessage.Type.PREPARE).remove(blockId);
        }
    }

    /** PRE-COMMIT PHASE FOR REPLICA **/
    public void onReceivePreCommitMsg(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PREPARE, curView.get()))
            return;

        this.prepareQC = m.getJustify();

        Block node = storage.getBlock(prepareQC.getBlockId());

        byte[] dummySig = new byte[0]; //TODO - Replace with real crypto later
        HotStuffMessage vote = utils.voteMsg(HotStuffMessage.Type.PRE_COMMIT, curView.get(), node, prepareQC,  dummySig);

        String leaderId = getLeader(curView.get());
        serverContext.getPerfectLink().send(leaderId, vote.toByteArray());

        System.out.println("[REPLICA] - Voted PRE-COMMIT for view " + curView.get());
    }

    /** COMMIT PHASE FOR LEADER **/
    public void onReceivePreCommitVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.PRE_COMMIT, curView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PRE_COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.PRE_COMMIT)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && amILeaderOfView(vote.getViewNumber())){
            byte[] aggregatedSig = new byte[0]; //TODO - Replace with real crypto later
            QC preCommitQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage commitMsg = utils.msg(HotStuffMessage.Type.COMMIT, curView.get(), null, preCommitQC);
            broadcast(commitMsg);

            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** COMMIT PHASE FOR REPLICA **/
    public void onReceiveCommit(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PRE_COMMIT, curView.get()))
            return;

        this.lockedQC = m.getJustify();

        System.out.println("[REPLICA] - Locked QC for view " + curView.get());

        Block node = storage.getBlock(lockedQC.getBlockId());
        byte[] dummySig = new byte[0]; //TODO - Replace with real crypto later
        HotStuffMessage vote = utils.voteMsg(HotStuffMessage.Type.COMMIT, curView.get(), node, lockedQC, dummySig);

        String leaderId = getLeader(curView.get());
        serverContext.getPerfectLink().send(leaderId, vote.toByteArray());
        System.out.println("[REPLICA] - Voted COMMIT for view " + curView.get());
    }

    /** DECIDE PHASE FOR LEADER **/
    public void onReceiveCommitVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.COMMIT, curView.get()))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();
        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.COMMIT)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && amILeaderOfView(vote.getViewNumber())){
            byte[] aggregatedSig = new byte[0];
            QC commitQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage decideMSG = utils.msg(HotStuffMessage.Type.DECIDE, curView.get(), null, commitQC);
            broadcast(decideMSG);
            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** DECIDE PHASE FOR REPLICA **/
    public void onReceiveDecide(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.COMMIT, curView.get()))
            return;

        Block commitedBlock = storage.getBlock(m.getJustify().getBlockId());
        //executeBlock(commitedBlock); TODO - SOMEHOW SEND THE BLOCK TO BE EXECUTED

        startNextView();
    }

    /** HELPER FUNCTIONS **/
    public void broadcast(HotStuffMessage m){
        Set<String> destinations = serverContext.getConfig().getBlockChainServers().keySet();
        serverContext.getPerfectLink().broadcast(destinations, m.toByteArray());
    }

    public void addClientCmd(Command cmd){
        this.commandMempool.add(cmd);
    }

    public synchronized void startNextView(){
        int oldView = curView.get();
        int newView = curView.incrementAndGet();
        voteCollector.clear();

        HotStuffMessage newViewMSG = utils.msg(HotStuffMessage.Type.NEW_VIEW, oldView, null, prepareQC);
        String nextLeader = getLeader(newView);

        System.out.println("[VIEW CHANGE] - Moving to view " + newView + " with leader " + nextLeader);

        byte[] payload = newViewMSG.toByteArray();
        serverContext.getPerfectLink().send(nextLeader, payload);
    }
}