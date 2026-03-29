package ist.depchain.core.hotstuff;

import com.google.protobuf.ByteString;

import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.Block;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.ClientRequestMeta;
import ist.depchain.common.ClientResponse;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.common.Transaction;
import ist.depchain.common.TransactionPayload;
import ist.depchain.core.ServerContext;
import ist.depchain.core.blockchain.BlockBuilder;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.byzantine.MaliciousUtils;
import org.hyperledger.besu.datatypes.Address;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import ist.depchain.core.blockchain.BlockValidator;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.ValidationResult;

public class BasicHotStuffCoordinator {
    public interface RequestCommitListener {
        // same arguments as MessageHandler.markRequestExecuted, that is the main use case for this callback
        // but named to reflect the semantics of being called when a request is committed, rather than the internal implementation of marking it executed in the MessageHandler
        void onRequestCommitted(String clientId, int requestId);
    }

    protected final ServerContext serverContext;
    protected final BasicHotStuffUtils utils;
    protected final AtomicInteger currentView = new AtomicInteger(1); // incremented either by finishing a decision or by a NEXT_VIEW interrupt
    private int n;
    private int f;
    private final int hotStuffQuorum; // n - f, the number of votes needed to form a QC

    private QC lockedQC; // [PHASE 3] - The latest QC that was locked (highest QC - a.k.a. lockedQC.viewNumber - for which the replica voted COMMIT)
    private QC prepareQC; // [PHASE 1] - The latest prepare QC (highest QC for which the replica voted PRE-COMMIT)

    private final Map<HotStuffMessage.Type, Map<ByteString, Map<String, HotStuffMessage>>> voteCollector = new ConcurrentHashMap<>(); // phase -> blockId -> set of votes (maps from voterId to vote message)
    private final Map<Integer, Map<String, HotStuffMessage>> newViewMsgs = new ConcurrentHashMap<>();
    protected final BasicHotStuffTree tree = new BasicHotStuffTree();
    private final CommandMempool mempool = new CommandMempool();

    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> viewTimerFuture;
    private static final long INITIAL_TIMEOUT_MS = 10000; // 10 seconds
    private static final long MAX_TIMEOUT_MS = 60000;
    private long currentTimeoutMs = INITIAL_TIMEOUT_MS;
    private int consecutiveTimeouts = 0;
    private static final int BACKOFF_THRESHOLD = 2; // start backoff after 2 consecutive failures

    private RequestCommitListener requestCommitListener; // callback to notify MessageHandler when a request is committed, so it can mark it as executed
    private final Set<ByteString> executedBlockIds = ConcurrentHashMap.newKeySet(); // blocks whose commands have been executed; guards against double-execution on DECIDE retransmission
    private static final class PendingDecide {
        private final String sourceId;
        private final HotStuffMessage message;

        private PendingDecide(String sourceId, HotStuffMessage message) {
            this.sourceId = sourceId;
            this.message = message;
        }
    }
    private final Map<ByteString, PendingDecide> pendingDecides = new ConcurrentHashMap<>();

    private boolean quorumReady = false;
    private List<ClientRequest> lastProposedBatch = null; // track in-flight proposal so we can re-enqueue on timeout

    private Thread proposalLoopThread;

    public BasicHotStuffCoordinator(ServerContext serverContext, boolean isByzantine) {
        this.serverContext = serverContext;
        this.utils = !isByzantine? new BasicHotStuffUtils(this.tree, serverContext.getBlsThresholdSig()): new MaliciousUtils(this.tree, serverContext.getBlsThresholdSig());
        this.n = serverContext.getConfig().getN();
        this.f = serverContext.getConfig().getF();
        this.hotStuffQuorum = (n - f);
        this.prepareQC = this.utils.getGenesisQC();
        this.lockedQC = this.utils.getGenesisQC();
    }

    public void stop() {
        timerExecutor.shutdownNow();
        if (proposalLoopThread != null) {
            proposalLoopThread.interrupt();
        }
    }

    public void start() {
        proposalLoopThread = new Thread(this::proposalLoop, "hotstuff-proposer");
        proposalLoopThread.setDaemon(true);
        proposalLoopThread.start(); // async proposal

        HotStuffMessage newViewMsg = utils.msg(HotStuffMessage.Type.NEW_VIEW, null, prepareQC, 0);
        ApplicationMessage wrapper = ApplicationMessage.newBuilder()
                                        .setHotstuffMessage(newViewMsg)
                                        .build();
        String leader = getLeaderForView(1);
        byte[] payload = wrapper.toByteArray();

        new Thread(() -> {
            try {
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
                    // wait for: (1) quorum ready, (2) mempool not empty, AND (3) sufficient fees
                    while (!quorumReady || mempool.isEmpty() || !hasSufficientFees()) {
                        wait();
                    }
                    System.out.println("[COORDINATOR | PROPOSER] view=" + currentView.get() + " quorumReady=" + quorumReady + " mempoolSize>0=" + !mempool.isEmpty());
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
        System.out.println("[COORDINATOR | IN] source=" + sourceId + " type=" + m.getType() + " view=" + m.getViewNumber() + " currentView=" + currentView.get() + " hasSig=" + !m.getPartialSig().isEmpty());
        
        // only restart the timer for messages that represent progress in the current view, future-view messages are buffered but should noti delay our own timeout
        boolean isCurrentView = (m.getViewNumber() == currentView.get());
        boolean isCurrentNewView = (m.getType() == HotStuffMessage.Type.NEW_VIEW && m.getViewNumber() == currentView.get() - 1);
        if (isCurrentView || isCurrentNewView) {
            restartTimer();
        }

        if (m.getViewNumber() < currentView.get() && m.getType() != HotStuffMessage.Type.NEW_VIEW && m.getType() != HotStuffMessage.Type.DECIDE && m.getType() != HotStuffMessage.Type.PREPARE) {
            // we can still accept old NEW_VIEW messages to buffer them for our next view, and we must accept old DECIDE and PREPARE messages to be able to execute decisions based on them
            // but other old messages are not useful and may represent delayed or replayed messages that should not interfere with our current state, so we ignore them
            System.out.println("[COORDINATOR | DROP] old message ignored source=" + sourceId + " type=" + m.getType() + " view=" + m.getViewNumber() + " currentView=" + currentView.get());
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
                System.out.println("[COORDINATOR | NEW_VIEW] leader=" + serverContext.getConfig().getSelfId() + " buffered view=" + view + " targetView=" + targetView
                    + " source=" + sourceId
                    + " count=" + count
                    + " currentView=" + currentView.get());

            // but only signal the proposalLoop if we've actually advanced to targetView already; if not, the messages are buffered, startNextView() checks them on arrival
            if (reachedQuorum(count) && currentView.get() == targetView) {
                quorumReady = true;
                notify();
            }
        }
    }

    protected void doPropose() {
        int oldView = currentView.get() - 1;
        Map<String, HotStuffMessage> msgs = newViewMsgs.getOrDefault(oldView, new HashMap<>());

        System.out.println("[COORDINATOR | PROPOSE] currentView=" + currentView.get() + " oldView=" + oldView + " bufferedNewViewMsgs=" + msgs.size());

        QC highQC = utils.getGenesisQC();
        for (Map.Entry<String, HotStuffMessage> entry : msgs.entrySet()) {
            HotStuffMessage m = entry.getValue();
            if (!utils.verifyQC(m.getJustify())) continue;
            if (m.getJustify().getViewNumber() > highQC.getViewNumber()) {
                highQC = m.getJustify();
            }
        }

        Block parent = tree.getBlock(highQC.getBlockId());
        if (parent == null) {
            parent = tree.getGenesisBlock();
        }

        // Drain the best fee-sufficient batch, regardless of queue arrival order.
        List<ClientRequest> batch = mempool.drainFeeBatch(serverContext.getConfig().getMaxBatchSize(), BigInteger.valueOf(serverContext.getConfig().getMinFeeThreshold()));
        List<TransactionPayload> txPayloads = new ArrayList<>();
        List<ClientRequestMeta> metaList = new ArrayList<>();

        if (batch.isEmpty()) {
            System.out.println("[COORDINATOR | PROPOSE] no fee-sufficient batch available after wakeup");
            return;
        }

        System.out.println("[COORDINATOR | PROPOSE] selectedBatchSize=" + batch.size() + " blockParent=" + (parent == null ? "<genesis>" : parent.getId().toStringUtf8()) + " currentView=" + currentView.get());

        lastProposedBatch = batch;
        for (ClientRequest req : batch) {
            txPayloads.add(req.getTransaction());
            metaList.add(ClientRequestMeta.newBuilder()
                    .setClientId(req.getClientId())
                    .setRequestId(req.getRequestId())
                    .build());
        }

        Block curProposal = utils.createLeaf(parent, txPayloads, metaList);
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
        if (votes.containsKey(sourceId)) {
            // means that the same replica is sending multiple PREPARE votes for the same block and view, which should not happen in a correct execution
            // we ignore subsequent votes from the same replica to prevent them from interfering with our vote counting, but we do not restart the timer 
            // as this may be a Byzantine replica trying to disrupt the protocol by spamming votes
            return;
        }
        votes.put(sourceId, vote);
        System.out.println("[COORDINATOR | VOTE] PREPARE source=" + sourceId + " view=" + vote.getViewNumber() + " block=" + blockId.toStringUtf8() + " count=" + votes.size());

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
        if (votes.containsKey(sourceId)) {
            // means that the same replica is sending multiple PRE_COMMIT votes for the same block and view, which should not happen in a correct execution
            // we ignore subsequent votes from the same replica to prevent them from interfering with our vote counting, but we do not restart the timer 
            // as this may be a Byzantine replica trying to disrupt the protocol by spamming votes
            return;
        }
        votes.put(sourceId, vote);
        System.out.println("[COORDINATOR | VOTE] PRE_COMMIT source=" + sourceId + " view=" + vote.getViewNumber() + " block=" + blockId.toStringUtf8() + " count=" + votes.size());

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
        if (votes.containsKey(sourceId)) {
            // means that the same replica is sending multiple COMMIT votes for the same block and view, which should not happen in a correct execution
            // we ignore subsequent votes from the same replica to prevent them from interfering with our vote counting, but we do not restart the timer 
            // as this may be a Byzantine replica trying to disrupt the protocol by spamming votes
            return;
        }
        votes.put(sourceId, vote);
        System.out.println("[COORDINATOR | VOTE] COMMIT source=" + sourceId + " view=" + vote.getViewNumber() + " block=" + blockId.toStringUtf8() + " count=" + votes.size());

        if (amILeaderOfView(vote.getViewNumber()) && reachedQuorum(votes.size())) {
            QC commitQC = utils.createQC(votes.values());

            HotStuffMessage decideMsg = utils.msg(HotStuffMessage.Type.DECIDE, null, commitQC, currentView.get());
            broadcast(decideMsg);
            voteCollector.get(HotStuffMessage.Type.COMMIT).remove(blockId);
        }
    }

    /** PREPARE phase as Replica **/
    public void onReceivePrepare(String sourceId, HotStuffMessage m) {
        if (!getLeaderForView(m.getViewNumber()).equals(sourceId))
            return;

        System.out.println("[COORDINATOR | PREPARE] source=" + sourceId + " messageView=" + m.getViewNumber() + " currentView=" + currentView.get());

        Block node = m.getBlock();
        QC justify = m.getJustify();

        if (!utils.verifyQC(justify)) {
            return;
        }

        // Re-validate the entire proposed block before voting.
        // This defends against a Byzantine leader injecting invalid or forged txs.
        // Replicas must not vote for a block just because it has a valid QC chain. They must also verify the payload
        ValidationResult validation = BlockValidator.validateProposedBlock(node, serverContext.getWorldState(), serverContext.getConfig());

        if (!validation.isValid()) {
            System.err.println("[COORDINATOR | REPLICA] Rejecting proposed block " + node.getId().toStringUtf8()
                    + ": " + validation.getErrorMessage());
            return;
        }

        tree.putBlock(node);
        System.out.println("[COORDINATOR | PREPARE] cached block=" + node.getId().toStringUtf8() + " parent=" + node.getParentId().toStringUtf8() + " currentView=" + currentView.get());

        PendingDecide pendingDecide = pendingDecides.remove(node.getId());
        if (pendingDecide != null && executeDecideIfReady(pendingDecide.sourceId, pendingDecide.message)) {
            System.out.println("[COORDINATOR | PREPARE] executed pending DECIDE for block=" + node.getId().toStringUtf8());
            return;
        }

        if (m.getViewNumber() != currentView.get()) {
            System.out.println("[COORDINATOR | PREPARE] cached out-of-view block=" + node.getId().toStringUtf8() + " messageView=" + m.getViewNumber() + " currentView=" + currentView.get());
            return;
        }

        // We only cast a vote for the current view. Older/future views are cached so the block can still
        // be executed later if a matching DECIDE arrives.
        boolean extendsJustify = utils.extendsFrom(node, justify.getBlockId());
        boolean safeNode = utils.safeNode(node, justify, lockedQC);

        if (!extendsJustify || !safeNode) {
            return;
        }

        sendVote(HotStuffMessage.Type.PREPARE, node, null);
    }

    /** PRE-COMMIT phase as Replica **/
    public void onReceivePreCommit(String sourceId, HotStuffMessage m) {
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PREPARE, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        QC newPrepareQC = m.getJustify();
        if (!utils.verifyQC(newPrepareQC)) {
            return;
        }
        if (prepareQC != null && newPrepareQC.getViewNumber() < prepareQC.getViewNumber()) {
            // if the PRE-COMMIT justify QC is for a lower view than our current prepareQC, it means the leader is trying to make us vote for an old block that may no longer be safe
            // we should ignore it and not update our prepareQC, as this may be a Byzantine leader trying to disrupt the protocol by sending outdated PRE-COMMIT messages
            return;
        }
        this.prepareQC = newPrepareQC;
        System.out.println("[COORDINATOR | PRE_COMMIT] source=" + sourceId + " qcView=" + newPrepareQC.getViewNumber() + " block=" + newPrepareQC.getBlockId().toStringUtf8() + " currentView=" + currentView.get());

        Block node = tree.getBlock(prepareQC.getBlockId());
        if (node == null) {
            System.out.println("[COORDINATOR | PRE_COMMIT] missing block for qc blockId=" + prepareQC.getBlockId().toStringUtf8());
            return;
        }
        sendVote(HotStuffMessage.Type.PRE_COMMIT, node, prepareQC);
    }

    /** COMMIT phase as Replica **/
    public void onReceiveCommit(String sourceId, HotStuffMessage m) {
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.PRE_COMMIT, currentView.get())
                || !getLeaderForView(currentView.get()).equals(sourceId))
            return;

        QC newLockedQC = m.getJustify();
        if (!utils.verifyQC(newLockedQC)) {
            return;
        }
        if (lockedQC != null && newLockedQC.getViewNumber() < lockedQC.getViewNumber()) {
            // if the COMMIT justify QC is for a lower view than our current lockedQC, it means the leader is trying to make us vote for an old block that may no longer be safe
            // we should ignore it and not update our lockedQC, as this may be a Byzantine leader trying to disrupt the protocol by sending outdated COMMIT messages
            return;

        }
        this.lockedQC = newLockedQC;
        System.out.println("[COORDINATOR | COMMIT] source=" + sourceId + " qcView=" + newLockedQC.getViewNumber() + " block=" + newLockedQC.getBlockId().toStringUtf8() + " currentView=" + currentView.get());

        Block node = tree.getBlock(lockedQC.getBlockId());
        if (node == null) {
            System.out.println("[COORDINATOR | COMMIT] missing block for qc blockId=" + lockedQC.getBlockId().toStringUtf8());
            // if we don't have the block corresponding to the lockedQC, it means the leader is trying to make us vote for a block we don't know about, which should not happen in a correct execution
            // we ignore the COMMIT message as we cannot safely vote for it, but we do not update our view or prune the tree as this may be a Byzantine leader trying to disrupt the protocol by sending COMMIT messages for unknown blocks
            return;
        }
        sendVote(HotStuffMessage.Type.COMMIT, node, lockedQC);

    }

    /** DECIDE phase as Replica **/
    public void onReceiveDecide(String sourceId, HotStuffMessage m) {
        int decideView = m.getViewNumber();
        if (!utils.matchingQC(m.getJustify(), HotStuffMessage.Type.COMMIT, decideView)
                || !getLeaderForView(decideView).equals(sourceId))
            return;

        if (!utils.verifyQC(m.getJustify())) {
            return;
        }

        Block commitedBlock = tree.getBlock(m.getJustify().getBlockId());
        if (commitedBlock == null) {
            pendingDecides.put(m.getJustify().getBlockId(), new PendingDecide(sourceId, m));
            System.out.println("[COORDINATOR | DECIDE] pending unknown blockId=" + m.getJustify().getBlockId().toStringUtf8() + " source=" + sourceId + " decideView=" + decideView + " currentView=" + currentView.get());
            return;
        }

        executeDecideIfReady(sourceId, m);
    }

    private boolean executeDecideIfReady(String sourceId, HotStuffMessage m) {
        Block commitedBlock = tree.getBlock(m.getJustify().getBlockId());
        if (commitedBlock == null) {
            pendingDecides.put(m.getJustify().getBlockId(), new PendingDecide(sourceId, m));
            System.out.println("[COORDINATOR | DECIDE] still pending unknown blockId=" + m.getJustify().getBlockId().toStringUtf8() + " source=" + sourceId + " decideView=" + m.getViewNumber());
            return false;
        }

        ByteString blockId = commitedBlock.getId();

        // guard against double-execution: only execute once per block
        boolean isFirstExecution = executedBlockIds.add(blockId);
        if (!isFirstExecution) {
            pendingDecides.remove(blockId);
            return false;
        }

        int decideView = m.getViewNumber();
        System.out.println("[COORDINATOR | DECIDE] executing block=" + blockId.toStringUtf8() + " source=" + sourceId + " decideView=" + decideView + " currentViewBefore=" + currentView.get());
        executeStage2Block(commitedBlock, sourceId);

        if (currentView.get() < decideView) {
            // this means we are behind the decided view, likely due to a missed DECIDE message or a slow timer
            // we fast-forward our view to the (safely) decided view to avoid losing future decisions.
            // if we end up jumping views, it's okay, since we also accept DECIDE messages from previous views.
            currentView.set(decideView);
        }

        pendingDecides.remove(blockId);
        tree.pruneSiblings(commitedBlock);
        onCommit();
        startNextView();
        System.out.println("[COORDINATOR | DECIDE] finished execution block=" + blockId.toStringUtf8() + " currentViewAfter=" + currentView.get());
        return true;
    }

    /**
     * Stage 2: execute all transactions in the decided block, build an application Block,
     * persist it, and send per-transaction ClientResponses.
     * 
     * Makes block commit atomic at block level:
     * - simulate all txs on a copy,
     * - compute the resulting deterministic state hash,
     * - then publish the snapshot into the committed world state.
     */
    private void executeStage2Block(Block protoBlock, String leaderId) {
        List<ClientRequestMeta> metaList = protoBlock.getRequestMetaList();
        List<TransactionReceipt> receipts = new ArrayList<>();
        BlockChainBlock finalBlock;

        synchronized (serverContext) {
            // 1. Decode txs
            List<Transaction> txList = BlockValidator.decodeTransactionsFromProto(protoBlock);

            // 2. Determine proposer address
            Address proposerAddress = serverContext.deriveAddressForProcess(leaderId);
            System.out.println("[COORDINATOR] Executing block " + protoBlock.getId() + " proposed by " + leaderId
                    + " with " + txList.size() + " transactions");

            // 3. Build deterministic app-level block
            BlockChainBlock previousAppBlock = serverContext.getBlockChain().getLatestBlock();
            BlockChainBlock appBlock = BlockBuilder.build(txList, previousAppBlock, proposerAddress);

            // 4. Execute on an isolated snapshot, not on the committed world state directly
            DepChainWorldState workingState = serverContext.getWorldState().copy();

            for (Transaction tx : appBlock.getTransactions()) {
                TransactionReceipt receipt = serverContext.getTransactionExecutor().execute(workingState, tx, proposerAddress);
                receipts.add(receipt);
            }

            // 5. Compute resulting state hash and finalize block
            String stateHash = workingState.computeStateHash();
            finalBlock = BlockBuilder.finalize(appBlock, receipts, stateHash);

            // 6. Atomically publish the new committed state + persist block
            serverContext.getWorldState().replaceWith(workingState);
            serverContext.getBlockChain().addBlock(finalBlock);

            System.out.println("[COORDINATOR] Committed block #" + finalBlock.getBlockNumber() + " with " + txList.size() + " txs, hash=" + finalBlock.getBlockHash() + ", stateHash=" + stateHash);

            // 7. Discard committed requests from mempool and notify MessageHandler
            synchronized (this) {
                lastProposedBatch = null;
                for (ClientRequestMeta meta : metaList) {
                    mempool.discardIfPresent(meta.getClientId(), meta.getRequestId());
                }
            }

            if (requestCommitListener != null) {
                for (ClientRequestMeta meta : metaList) {
                    requestCommitListener.onRequestCommitted(meta.getClientId(), meta.getRequestId());
                }
            }
        }

        // 8. Send per-request client responses outside the state lock.
        for (int i = 0; i < metaList.size(); i++) {
            ClientRequestMeta meta = metaList.get(i);
            TransactionReceipt receipt = receipts.get(i);

            ClientResponse.Builder responseBuilder = ClientResponse.newBuilder()
                    .setClientId(meta.getClientId())
                    .setRequestId(meta.getRequestId())
                    .setCommitted(true)
                    .setBlockId(ByteString.copyFromUtf8(finalBlock.getBlockHash()))
                    .setTxHash(ByteString.copyFrom(receipt.getTxHash()))
                    .setStatus(receipt.isSuccess() ? "COMMITTED_SUCCESS" : "COMMITTED_FAILURE")
                    .setError(receipt.getError() == null ? "" : receipt.getError());

            if (receipt != null) {
                responseBuilder
                        .setReturnData(ByteString.copyFrom(receipt.getReturnData()))
                        .setGasUsed(ByteString.copyFrom(receipt.getGasUsed().toByteArray()))
                        .setFee(ByteString.copyFrom(receipt.getFee().toByteArray()));

                if (receipt.getContractAddress() != null) {
                    responseBuilder.setContractAddress(ByteString.copyFrom(receipt.getContractAddress().toArrayUnsafe()));
                }
            }

            ClientResponse response = responseBuilder.build();

            try {
                serverContext.getPerfectLink().send(meta.getClientId(), response.toByteArray());
            } catch (Exception e) {
                System.err.println("[COORDINATOR | ERROR] Failed to send response to " + meta.getClientId() + ": " + e.getMessage());
            }
        }
    }

    /** HELPER FUNCTIONS **/

    /**
     * Checks if the next batch in the mempool has sufficient total fees to meet the threshold.
     * Peeks at the batch without removing it.
     */
    private boolean hasSufficientFees() {
        System.out.println("[COORDINATOR] Checking fees for next batch in mempool...");
        List<ClientRequest> batch = mempool.peekFeeBatch(serverContext.getConfig().getMaxBatchSize(), BigInteger.valueOf(serverContext.getConfig().getMinFeeThreshold()));
        if (batch.isEmpty()) {
            return false;  // No requests to propose
        }
        BigInteger totalFees = BigInteger.ZERO;
        for (ClientRequest req : batch) {
            if (req.hasTransaction()) {
                totalFees = totalFees.add(new BigInteger(1, req.getTransaction().getGasPrice().toByteArray()).multiply(new BigInteger(1, req.getTransaction().getGasLimit().toByteArray())));
            }
        }
        System.out.println("[COORDINATOR] Current batch total fees: " + totalFees + " / " + serverContext.getConfig().getMinFeeThreshold());
        return totalFees.compareTo(BigInteger.valueOf(serverContext.getConfig().getMinFeeThreshold())) >= 0;
    }

    // Method to start a new view
    public synchronized void startNextView() {
        consecutiveTimeouts++;
        if (consecutiveTimeouts > BACKOFF_THRESHOLD) {
            currentTimeoutMs = Math.min(currentTimeoutMs * 2, MAX_TIMEOUT_MS);
        }

        // Re-enqueue the proposed batch if the view ended without a successful DECIDE
        if (lastProposedBatch != null) {
            for (ClientRequest req : lastProposedBatch) {
                mempool.enqueue(req);
            }
            lastProposedBatch = null;
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
                quorumReady = true;
                notifyAll();
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
        notifyAll(); 
    }

    public synchronized void setRequestCommitListener(RequestCommitListener listener) {
        this.requestCommitListener = listener;
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

    // === Test inspection getters ===

    public BasicHotStuffTree getTree() {
        return tree;
    }

    public synchronized QC getLockedQC() {
        return lockedQC;
    }

    public synchronized QC getPrepareQC() {
        return prepareQC;
    }

    public int getCurrentView() {
        return currentView.get();
    }

    public Set<ByteString> getExecutedBlockIds() {
        return executedBlockIds;
    }

    public ServerContext getServerContext() {
        return serverContext;
    }
}