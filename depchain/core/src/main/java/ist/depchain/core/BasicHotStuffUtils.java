package ist.depchain.core;

import com.google.protobuf.ByteString;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.HotStuffMessage.Type;
import ist.depchain.common.Block;
import ist.depchain.common.QC;
import ist.depchain.common.Command;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class BasicHotStuffUtils {
    /* MESSAGES */

    // [Line 1-6] - Create base HotStuff Message
    public HotStuffMessage msg(Type type, int viewNumber, Block node, QC justify) {
        return HotStuffMessage.newBuilder()
                .setType(type)                // [Line 2]
                .setViewNumber(viewNumber)    // [Line 3]
                .setNode(node)                // [Line 4]
                .setJustify(justify)          // [Line 5]
                .build();
    }

    // [Line 7-10] - Create Vote Message
    public HotStuffMessage voteMsg(Type type, int viewNumber, Block node,  QC justify, byte[] partialSig) {
        // [Line 8] - m <- Msg(type, node, qc)
        HotStuffMessage m = msg(type, viewNumber, node, justify);

        // [Line 9-10] - m.partialSig <- tsignr(<m.type, m.viewNumber, m.node>)
        return m.toBuilder()
                .setPartialSig(ByteString.copyFrom(partialSig))
                .build();
    }

    /* TREE & QC */

    // [Line 11-14] - Create a new block (LEAF)
    public Block createLeaf(Block parent, Command cmd, byte[] hash){
        return Block.newBuilder()
                .setParent(parent.getHash())
                .setCmd(cmd)
                .setHash(ByteString.copyFrom(hash))
                .setHeight(parent.getHeight())
                .build();
    }

    // [Line 15-20] - Create QC
    public QC createQC(Collection<HotStuffMessage> v, byte[] combinedSig) {
        if ( v.isEmpty() ) {return null;}
        HotStuffMessage msg = v.iterator().next();

        return QC.newBuilder()
                .setType(msg.getType())                     // [Line 16]
                .setViewNumber(msg.getViewNumber())         // [Line 17]
                .setNodeHash(msg.getNode().getHash())       // [Line 18]
                .setSig(ByteString.copyFrom(combinedSig))   // [Line 19]
                .build();
    }

    /*  */

}
