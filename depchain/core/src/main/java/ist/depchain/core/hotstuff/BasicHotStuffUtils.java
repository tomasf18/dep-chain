package ist.depchain.core.hotstuff;

import com.google.protobuf.ByteString;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.HotStuffMessage.Type;
import ist.depchain.core.hotstuff.tsignatures.BLSThresholdSig;
import ist.depchain.common.Block;
import ist.depchain.common.QC;
import ist.depchain.common.Command;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class BasicHotStuffUtils {

    private final BasicHotStuffTree tree;
    private final BLSThresholdSig blsThresholdSig;

    public BasicHotStuffUtils(BasicHotStuffTree tree, BLSThresholdSig blsThresholdSig) {
        this.tree = tree;
        this.blsThresholdSig = blsThresholdSig;
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
    public HotStuffMessage voteMsg(HotStuffMessage.Type type, Block node, QC justify, int viewNumber) {
        ByteString blockId = (node != null) ? node.getId() : ByteString.EMPTY;
        byte[] digest = getMsgDigest(type, viewNumber, blockId);
        byte[] partialSig = blsThresholdSig.partialSign(digest); // partial signature

        HotStuffMessage.Builder builder = HotStuffMessage.newBuilder()
            .setType(type)
            .setViewNumber(viewNumber)
            .setPartialSig(ByteString.copyFrom(partialSig));
        if (node != null)    builder.setBlock(node);
        if (justify != null) builder.setJustify(justify);
        return builder.build();
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
    public QC createQC(Collection<HotStuffMessage> votes) {
        HotStuffMessage first = votes.iterator().next();
        ByteString blockId = first.getBlock().getId();

        List<byte[]> partialSigs = votes.stream()
            .map(v -> v.getPartialSig().toByteArray())
            .collect(Collectors.toList());

        byte[] thresholdSig = blsThresholdSig.combine(partialSigs); // threshold combination

        return QC.newBuilder()
            .setType(first.getType())
            .setViewNumber(first.getViewNumber())
            .setBlockId(blockId)
            .setThresholdSig(ByteString.copyFrom(thresholdSig))
            .build();
    }

    /* Matching and Safety Functions */

    public boolean verifyQC(QC qc) {
        if (qc == null || qc.equals(QC.getDefaultInstance())) return true; // genesis QC always valid
        // if (qc.getThresholdSig().isEmpty()) return true; // stub QC during transition

        byte[] digest = getMsgDigest(qc.getType(), qc.getViewNumber(), qc.getBlockId());
        return blsThresholdSig.verify(digest, qc.getThresholdSig().toByteArray()); // ← real verification
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
