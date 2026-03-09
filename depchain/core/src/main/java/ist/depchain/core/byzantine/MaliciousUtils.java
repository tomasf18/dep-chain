package ist.depchain.core.byzantine;

import ist.depchain.common.Block;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.core.hotstuff.BasicHotStuffTree;
import ist.depchain.core.hotstuff.BasicHotStuffUtils;
import ist.depchain.core.hotstuff.tsignatures.BLSThresholdSig;

public class MaliciousUtils extends BasicHotStuffUtils {

    public MaliciousUtils(BasicHotStuffTree tree, BLSThresholdSig blsThresholdSig) {
        super(tree, blsThresholdSig);
    }

    @Override
    public HotStuffMessage voteMsg(HotStuffMessage.Type type, Block node, QC qc, int view){
        System.out.println("[BYZANTINE REPLICA] - Sending altered vote");
        HotStuffMessage msg = super.msg(type, node, qc, view);

        return msg.toBuilder()
                .setBlock(msg.getBlock().toBuilder()
                        .setCommand(msg.getBlock().getCommand().toBuilder()
                                .setData("MALICIOUS DATA ALTERED BY BYZANTINE")
                                .build())
                        .build())
                .build();
    }

}
