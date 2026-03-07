package ist.depchain.core.byzantine;

import ist.depchain.common.Block;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.core.hotstuff.BasicHotStuffTree;
import ist.depchain.core.hotstuff.BasicHotStuffUtils;

public class MaliciousUtils extends BasicHotStuffUtils {

    public MaliciousUtils(BasicHotStuffTree tree) {
        super(tree);
    }

    @Override
    public HotStuffMessage voteMsg(HotStuffMessage.Type type, Block node, QC qc, int view){
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
