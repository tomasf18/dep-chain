package ist.depchain.core;

import com.google.protobuf.ByteString;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.HotStuffMessage.Type;
import ist.depchain.common.Block;
import ist.depchain.common.QC;
import ist.depchain.common.Command;

 import java.util.Collection;

 public class BasicHotStuffUtils {

     private final BlockStorage storage;

     public BasicHotStuffUtils() {
         storage = new BlockStorage();
     }

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
     public Block createLeaf(Block parent, Command cmd, int id){
         return Block.newBuilder()
                 .setParentId(parent.getId())
                 .setCmd(cmd)
                 .setId(id)
                 .build();
     }

     // [Line 15-20] - Create QC
     public QC createQC(Collection<HotStuffMessage> v, byte[] combinedSig) {
         if ( v.isEmpty() ) {return null;}
         HotStuffMessage msg = v.iterator().next();

         return QC.newBuilder()
                 .setType(msg.getType())                     // [Line 16]
                 .setViewNumber(msg.getViewNumber())         // [Line 17]
                 .setNodeId(msg.getNode().getId())       // [Line 18]
                 .setSig(ByteString.copyFrom(combinedSig))   // [Line 19]
                 .build();
     }

     /* MATCHING & SAFETY FUNCTIONS */

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
         boolean extendsLocked = extendsFrom(node, lockedQC.getNodeId());

         //[Line 27]
         boolean viewIsHigher = qc.getViewNumber() > lockedQC.getViewNumber();

         return extendsLocked || viewIsHigher;
     }

     /* HELPER FUNCTIONS */

     // [NEW FUNCTION] - Verify if "node" has antecessor "targethash"
     public boolean extendsFrom(Block node, int targetId){
         if(node.getId() == targetId){return true;}

         int currentParentId = node.getParentId();
         while(currentParentId != 0){
             if(currentParentId == targetId){return true;}

             Block parent = storage.getBlock(currentParentId);
             if(parent == null){break;}
             currentParentId = parent.getParentId();
         }
         return false;
     }

}
