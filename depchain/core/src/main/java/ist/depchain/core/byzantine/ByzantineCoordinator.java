package ist.depchain.core.byzantine;

import com.google.protobuf.ByteString;
import ist.depchain.common.*;
import ist.depchain.core.ServerContext;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

import java.util.Random;

public class ByzantineCoordinator extends BasicHotStuffCoordinator {
    public enum AttackType{ SILENT, EQUIVOCATE, CORRUPT, INVALID_QC }
    private AttackType currentAttack = AttackType.SILENT; // Default is silent, for timeouts and new views

    public ByzantineCoordinator(ServerContext serverContext) {
        super(serverContext, true);
    }

    public void setAttack(AttackType attackType){
        this.currentAttack = attackType;
    }

    @Override
    public void onReceivePrepareVote(String sourceId, HotStuffMessage message) {
        if(this.currentAttack == AttackType.SILENT)
            System.out.println("[BYZANTINE COORDINATOR] - Ignoring vote from: " + sourceId);
        else
            super.onReceivePrepareVote(sourceId, message);
    }

    @Override
    protected void doPropose(){
        if(this.currentAttack == AttackType.EQUIVOCATE) {
            System.out.println("[BYZANTINE COORDINATOR] - Leader equivocating: sending different blocks to different replicas");

            QC highQC = super.utils.getGenesisQC();
            Block parent = super.tree.getGenesisBlock();

            Command cmdA = Command.newBuilder().setData("BLOCK_A").build();
            Block blockA = super.utils.createLeaf(parent, cmdA);
            super.tree.putBlock(blockA);

            Command cmdB = Command.newBuilder().setData("BLOCK_B").build();
            Block blockB = super.utils.createLeaf(parent, cmdB);
            super.tree.putBlock(blockB);

            HotStuffMessage msgA = super.utils.msg(HotStuffMessage.Type.PREPARE, blockA, highQC, super.currentView.get());
            HotStuffMessage msgB = super.utils.msg(HotStuffMessage.Type.PREPARE, blockB, highQC, super.currentView.get());

            ApplicationMessage wrapA = ApplicationMessage.newBuilder().setHotstuffMessage(msgA).build();
            ApplicationMessage wrapB = ApplicationMessage.newBuilder().setHotstuffMessage(msgB).build();

            super.serverContext.getPerfectLink().send("s0", wrapA.toByteArray());
            super.serverContext.getPerfectLink().send("s2", wrapA.toByteArray());
            super.serverContext.getPerfectLink().send("s3", wrapB.toByteArray());
        }
        else if (this.currentAttack == AttackType.INVALID_QC) {
            System.out.println("[BYZANTINE COORDINATOR] - Proposing block with a forged (invalid) QC");

            // Build a QC with a non-zero viewNumber and random garbage as the threshold signature.
            // verifyQC() on honest replicas will fail the BLS check and reject the proposal.
            byte[] forgedSig = new byte[96];
            new Random().nextBytes(forgedSig);

            QC forgedQC = QC.newBuilder()
                    .setType(HotStuffMessage.Type.PREPARE)
                    .setViewNumber(super.currentView.get())
                    .setBlockId(super.tree.getGenesisBlock().getId())
                    .setThresholdSig(ByteString.copyFrom(forgedSig))
                    .build();

            Block leaf = super.utils.createLeaf(super.tree.getGenesisBlock(),
                    Command.newBuilder().setData("INVALID_QC_BLOCK").build());
            super.tree.putBlock(leaf);

            HotStuffMessage prepareMsg = super.utils.msg(HotStuffMessage.Type.PREPARE, leaf, forgedQC, super.currentView.get());
            ApplicationMessage wrapper = ApplicationMessage.newBuilder().setHotstuffMessage(prepareMsg).build();
            super.serverContext.getConfig().getBlockChainServers().keySet()
                    .forEach(id -> super.serverContext.getPerfectLink().send(id, wrapper.toByteArray()));
        }
        else if (this.currentAttack == AttackType.SILENT)
            System.out.println("[BYZANTINE COORDINATOR] - Staying silent");
        else
            super.doPropose();
    }
}
