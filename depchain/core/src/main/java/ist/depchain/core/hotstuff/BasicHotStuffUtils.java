package ist.depchain.core.hotstuff;

import com.google.protobuf.ByteString;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.HotStuffMessage.Type;
import ist.depchain.common.Block;
import ist.depchain.common.QC;
import ist.depchain.common.Command;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.UUID;

public class BasicHotStuffUtils {

    private final BasicHotStuffTree tree;

    public BasicHotStuffUtils(BasicHotStuffTree tree) {
        this.tree = tree;
    }

    public QC getGenesisQC() {
        return QC.newBuilder()
                .setType(Type.DECIDE)
                .setViewNumber(0)
                .setBlockId(ByteString.EMPTY)
                .setThresholdSig(ByteString.EMPTY)
                .build();
    }

    /* Messages */

    // [Line 1-6] - Create base HotStuff Message
    public HotStuffMessage msg(Type type, Block block, QC justify, int viewNumber) {
        HotStuffMessage.Builder builder = HotStuffMessage.newBuilder()
                .setType(type)
                .setViewNumber(viewNumber);
        if (block != null)
            builder.setBlock(block);
        if (justify != null)
            builder.setJustify(justify);
        return builder.build();
    }

    // [Line 7-10] - Create Vote Message
    public HotStuffMessage voteMsg(Type type, Block node, QC justify, int viewNumber) {
        HotStuffMessage m = msg(type, node, justify, viewNumber);
        // TODO - Replace with real crypto later -> tsignr(<m.type, m.viewNumber, m.node>)
        byte[] partialSign = getMsgDigest(type, viewNumber, node.getId());

        // [Line 9-10] - m.partialSig <- tsignr(<m.type, m.viewNumber, m.node>)
        return m.toBuilder()
                .setPartialSig(ByteString.copyFrom(partialSign))
                .build();
    }

    /* Tree & QC */

    // [Line 11-14] - Create a new block (LEAF)
    public Block createLeaf(Block parent, Command cmd) {
        ByteString newId = ByteString.copyFromUtf8(UUID.randomUUID().toString());
        return Block.newBuilder()
                .setParentId(parent.getId())
                .setId(newId)
                .setCommand(cmd)
                .build();
    }

    // [Line 15-20] - Create QC
    public QC createQC(Collection<HotStuffMessage> v) {
        if (v.isEmpty()) {
            return null;
        }
        HotStuffMessage msg = v.iterator().next();
        byte[] combinedSig = new byte[0]; // TODO - Replace with real crypto later -> tcombine({m.partialSig | m in v})

        return QC.newBuilder()
                .setType(msg.getType())
                .setViewNumber(msg.getViewNumber())
                .setBlockId(msg.getBlock().getId())
                .setThresholdSig(ByteString.copyFrom(combinedSig))
                .build();
    }

    /* Matching and Safety Functions */

    public boolean verifyQC(QC qc) {
        // TODO - Replace with real crypto later -> tverify(qc.thresholdSig, <qc.type, qc.viewNumber, qc.blockId>)
        return true;
    }

    // [Line 21-22] - Verify if Message is the same
    public boolean matchingMSG(HotStuffMessage m, Type t, int v) {
        return m.getType() == t && m.getViewNumber() == v;
    }

    // [Line 23-24] - Verify if QC coincides with the expected type and view number
    public boolean matchingQC(QC qc, Type t, int v) {
        return qc.getType() == t && qc.getViewNumber() == v;
    }

    // [Line 26-27] - Check if node is safe
    public boolean safeNode(Block node, QC qc, QC lockedQC) {
        if (lockedQC == null || lockedQC.getViewNumber() == 0)
            return true;

        // [Line 26]
        boolean extendsLocked = extendsFrom(node, lockedQC.getBlockId());

        // [Line 27]
        boolean viewIsHigher = qc.getViewNumber() > lockedQC.getViewNumber();

        return extendsLocked || viewIsHigher;
    }

    /* Helper Functions */

    // Verify if "node" has antecessor "targethash"
    public boolean extendsFrom(Block node, ByteString targetId) {
        if (node.getId().equals(targetId)) {
            return true;
        }

        // handle genesis case: if targetId is empty, any block extends from genesis
        if (targetId.isEmpty()) {
            return true;
        }

        ByteString currentParentId = node.getParentId();
        while (!currentParentId.isEmpty()) {
            if (currentParentId.equals(targetId)) {
                return true;
            }

            Block parent = tree.getBlock(currentParentId);
            if (parent == null) {
                break;
            }
            currentParentId = parent.getParentId();
        }
        return false;
    }

    // [Line 9] - Get the digest of a message for signing
    public byte[] getMsgDigest(Type type, int viewNumber, ByteString blockId) {
        return ByteBuffer.allocate(4 + 4 + blockId.size())
                .putInt(type.getNumber())
                .putInt(viewNumber)
                .put(blockId.toByteArray())
                .array();
    }
}
