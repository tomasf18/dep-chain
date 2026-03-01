package ist.depchain.core;

import com.google.protobuf.ByteString;
import ist.depchain.common.*;
import ist.depchain.common.Block;
import ist.depchain.common.ClientResponse;
import ist.depchain.common.Command;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.core.hotstuff.*;

import java.util.*;

public class BasicHotStuffProtocol {
    private final BasicHotStuffUtils utils;
    private final BlockStorage storage;
    private final ServerContext serverContext;
    private int n, f;
    private int curView = 1;

    // Safety State
    private QC lockedQC = BasicHotStuffUtils.genesisQC;  // [PHASE 3] - The latest QC that was locked
    private QC prepareQC = BasicHotStuffUtils.genesisQC; // [PHASE 1] - The latest prepare QC

    // Phase Tracking
    private final Map<HotStuffMessage.Type, Map<ByteString, Set<HotStuffMessage>>> voteCollector = new HashMap<>();
    private final Map<Integer, Set<HotStuffMessage>> newViewMsgs = new HashMap<>();
    private final Queue<Command> commandMempool = new LinkedList<>();

    public BasicHotStuffProtocol(ServerContext serverContext, int n, int f) {
        this.serverContext = serverContext;
        this.n = n;
        this.f = f;
        this.storage = new BlockStorage();
        this.utils = new BasicHotStuffUtils(this.storage);
    }

    // TODO - DECIDE LEADER NEEDS FIX
    public String getLeader(int view){
        Object[] servers = serverContext.getConfig().getBlockChainServers().keySet().toArray();
        return (String)servers[view];
    }

    // TODO - ISLEADER NEEDS FIX, THIS IS JUST FOR TESTING PURPOSES
    public boolean isLeader(){
        String id = serverContext.getConfig().getSelfId();
        return getLeader(curView % serverContext.getConfig().getBlockChainServers().size()).equals(id);
    }

    /** PREPARE PHASE FOR LEADER **/
    public void onReceiveNewView(HotStuffMessage m){
        int view = m.getViewNumber();
        newViewMsgs.computeIfAbsent(view, k -> new HashSet<>()).add(m);

        if(newViewMsgs.get(view).size() >= (n-f) && isLeader()){
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

            HotStuffMessage prepareMsg = utils.msg(HotStuffMessage.Type.PREPARE, curView, curProposal, highQC);
            broadcast(prepareMsg);

            newViewMsgs.remove(view);
        }
    }

    /** PREPARE PHASE FOR REPLICA **/
    public void onReceivePrepare(HotStuffMessage m){
        if(!utils.matchingMSG(m, HotStuffMessage.Type.PREPARE, curView))
            return;

        Block node = m.getBlock();
        QC justify = m.getJustify();

        boolean extendsJustify = utils.extendsFrom(node, justify.getBlockId());
        boolean safeNode = utils.safeNode(node, justify, lockedQC);

        if(extendsJustify && safeNode){
            storage.putBlock(node);

            byte[] dummySig = new byte[0]; //TODO - Replace with real crypto later
            HotStuffMessage vote = utils.voteMsg(HotStuffMessage.Type.PREPARE, curView, node, justify, dummySig);

            String leaderId = getLeader(curView);
            serverContext.getPerfectLink().send(leaderId, vote.toByteArray());

            System.out.println("[REPLICA] - Voted PREPARE for Block: " + node.getId().toStringUtf8());
        }
    }

    /** PRE-COMMIT PHASE FOR LEADER **/
    public void onReceivePrepareVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.PREPARE, curView))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PREPARE, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Set<HotStuffMessage> votes =  voteCollector.get(HotStuffMessage.Type.PREPARE)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && isLeader()){
            byte[] aggregatedSig = new byte[0]; //TODO - Replace with real crypto later
            this.prepareQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage preCommitMsg = utils.msg(HotStuffMessage.Type.PRE_COMMIT, curView, null, prepareQC);
            broadcast(preCommitMsg);

            voteCollector.get(HotStuffMessage.Type.PREPARE).remove(blockId);
        }
    }

    /** PRE-COMMIT PHASE FOR REPLICA **/
    public void onReceivePreCommitMsg(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PREPARE, curView))
            return;

        this.prepareQC = m.getJustify();

        Block node = storage.getBlock(prepareQC.getBlockId());

        byte[] dummySig = new byte[0]; //TODO - Replace with real crypto later
        HotStuffMessage vote = utils.voteMsg(HotStuffMessage.Type.PRE_COMMIT, curView, node, prepareQC,  dummySig);

        String leaderId = getLeader(curView);
        serverContext.getPerfectLink().send(leaderId, vote.toByteArray());

        System.out.println("[REPLICA] - Voted PRE-COMMIT for view " + curView);
    }

    /** COMMIT PHASE FOR LEADER **/
    public void onReceivePreCommitVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.PRE_COMMIT, curView))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.PRE_COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();

        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.PRE_COMMIT)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && isLeader()){
            byte[] aggregatedSig = new byte[0]; //TODO - Replace with real crypto later
            QC preCommitQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage commitMsg = utils.msg(HotStuffMessage.Type.COMMIT, curView, null, preCommitQC);
            broadcast(commitMsg);

            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** COMMIT PHASE FOR REPLICA **/
    public void onReceiveCommit(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PRE_COMMIT, curView))
            return;

        this.lockedQC = m.getJustify();

        System.out.println("[REPLICA] - Locked QC for view " + curView);

        Block node = storage.getBlock(lockedQC.getBlockId());
        byte[] dummySig = new byte[0]; //TODO - Replace with real crypto later
        HotStuffMessage vote = utils.voteMsg(HotStuffMessage.Type.COMMIT, curView, node, lockedQC, dummySig);

        String leaderId = getLeader(curView);
        serverContext.getPerfectLink().send(leaderId, vote.toByteArray());
        System.out.println("[REPLICA] - Voted COMMIT for view " + curView);
    }

    /** DECIDE PHASE FOR LEADER **/
    public void onReceiveCommitVote(String sourceId, HotStuffMessage vote){
        if(!utils.matchingMSG(vote, HotStuffMessage.Type.COMMIT, curView))
            return;

        voteCollector.putIfAbsent(HotStuffMessage.Type.COMMIT, new HashMap<>());
        ByteString blockId = vote.getBlock().getId();
        Set<HotStuffMessage> votes = voteCollector.get(HotStuffMessage.Type.COMMIT)
                .computeIfAbsent(blockId, k -> new HashSet<>());
        votes.add(vote);

        if(votes.size() >= (n-f) && isLeader()){
            byte[] aggregatedSig = new byte[0];
            QC commitQC = utils.createQC(votes, aggregatedSig);

            HotStuffMessage decideMSG = utils.msg(HotStuffMessage.Type.DECIDE, curView, null, commitQC);
            broadcast(decideMSG);
            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** DECIDE PHASE FOR REPLICA **/
    public void onReceiveDecide(HotStuffMessage m){
        if(!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.COMMIT, curView))
            return;

        Block commitedBlock = storage.getBlock(m.getJustify().getBlockId());
        executeBlock(commitedBlock);

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

    public void executeBlock(Block block){
        System.out.println("[REPLICA] - Executing block " + block.getId());

        ist.depchain.common.ClientResponse response = ClientResponse.newBuilder()
                //.setClientId(block.getCommand().getClientId()) TODO - NEEDS CLIENT ID
                //.setRequestId(block.getCommand().getRequestId()) TODO - NEEDS REQUEST ID
                .setCommitted(true)
                .setBlockId(block.getId())
                .build();

        //serverContext.getPerfectLink().send(block.getCommand().getClientId(), response.toByteArray()); //TODO - NEED TO KNOW CLIENT ID
    }

    public synchronized void startNextView(){
        curView++;

        voteCollector.clear();

        String nextLeader = getLeader(curView);

        HotStuffMessage newViewMSG = utils.msg(HotStuffMessage.Type.NEW_VIEW, curView - 1, null, prepareQC);

        System.out.println("[VIEW CHANGE] - Moving to view " + curView + " with leader " + nextLeader);

        byte[] payload = newViewMSG.toByteArray();
        serverContext.getPerfectLink().send(nextLeader, payload);
    }
}