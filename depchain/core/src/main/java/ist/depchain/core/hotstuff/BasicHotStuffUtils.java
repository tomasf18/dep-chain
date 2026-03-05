package ist.depchain.core.hotstuff;
// package ist.depchain.core;

import com.google.protobuf.ByteString;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.HotStuffMessage.Type;
import ist.depchain.common.Block;
import ist.depchain.common.QC;
import ist.depchain.common.Command;

import java.nio.ByteBuffer;
import java.util.Collection;

 public class BasicHotStuffUtils {

     private final BlockStorage storage;

     public static final QC genesisQC = QC.newBuilder()
             .setType(Type.DECIDE)
             .setViewNumber(0)
             .setBlockId(ByteString.EMPTY)
             .setThresholdSig(ByteString.EMPTY)
             .build();

     public BasicHotStuffUtils(BlockStorage storage) {
         this.storage = storage;
     }

     /* Messages */

     // [Line 1-6] - Create base HotStuff Message
     public HotStuffMessage msg(Type type, Block block, QC justify, int viewNumber) {
         return HotStuffMessage.newBuilder()
                 .setType(type)
                 .setViewNumber(viewNumber)    
                 .setBlock(block)              
                 .setJustify(justify)          
                 .build();
     }

     // [Line 7-10] - Create Vote Message
     public HotStuffMessage voteMsg(Type type, Block node,  QC justify, int viewNumber, byte[] partialSig) {
         HotStuffMessage m = msg(type, node, justify, viewNumber);

         // [Line 9-10] - m.partialSig <- tsignr(<m.type, m.viewNumber, m.node>)
         return m.toBuilder()
                 .setPartialSig(ByteString.copyFrom(partialSig))
                 .build();
     }

     /* Tree & QC */

     // [Line 11-14] - Create a new block (LEAF)
     public Block createLeaf(Block parent, Command cmd, ByteString id){
         return Block.newBuilder()
                 .setParentId(parent.getId())
                 .setId(id)
                 .setCommand(cmd)
                 .build();
     }

     // [Line 15-20] - Create QC
     public QC createQC(Collection<HotStuffMessage> v, byte[] combinedSig) {
         if ( v.isEmpty() ) {return null;}
         HotStuffMessage msg = v.iterator().next();

         return QC.newBuilder()
                 .setType(msg.getType())
                 .setViewNumber(msg.getViewNumber())                    
                 .setBlockId(msg.getBlock().getId())                    
                 .setThresholdSig(ByteString.copyFrom(combinedSig))     
                 .build();
     }

     /* Matching and Safety Functions */

     // [Line 21-22] - Verify if Message is the same
     public boolean matchingMSG(HotStuffMessage m, Type t, int v) {
         return m.getType() == t && m.getViewNumber() == v;
     }

     // [Line 23-24] - Verify if QC coincides with the expected type and view number
     public boolean matchingQC(QC qc, Type t, int v){
         return qc.getType() == t && qc.getViewNumber() == v;
     }

     // [Line 26-27] - Check if node is safe
     public boolean safeNode(Block node, QC qc, QC lockedQC){
         if(lockedQC == null || lockedQC.getViewNumber() == 0) return true;

         //[Line 26]
         boolean extendsLocked = extendsFrom(node, lockedQC.getBlockId());

         //[Line 27]
         boolean viewIsHigher = qc.getViewNumber() > lockedQC.getViewNumber();

         return extendsLocked || viewIsHigher;
     }

     /* Helper Functions */

     // Verify if "node" has antecessor "targethash"
     public boolean extendsFrom(Block node, ByteString targetId){
         if(node.getId() == targetId){return true;}

         ByteString currentParentId = node.getParentId();
         while(!currentParentId.isEmpty()){
             if(currentParentId.equals(targetId)){return true;}

             Block parent = storage.getBlock(currentParentId);
             if(parent == null){break;}
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
