package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.UUID;

import com.google.protobuf.ByteString;

import org.junit.jupiter.api.Test;

import ist.depchain.common.Block;
import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerContext;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

class RepeatedDecideIdempotenceTest {

    @Test
    void repeatedDecideStyleExecutionAppliesACommittedBlockOnlyOnce() throws Exception {
        Config config = Config.loadConfiguration("../config/config-dev.json", "s0");
        ServerContext serverContext = new ServerContext(config);
        BasicHotStuffCoordinator coordinator = new BasicHotStuffCoordinator(serverContext, false);

        Block block = Block.newBuilder()
                .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                .setParentId(ByteString.EMPTY)
                .build();

        coordinator.getTree().putBlock(block);

        QC decideQc = QC.newBuilder()
                .setType(HotStuffMessage.Type.COMMIT)
                .setViewNumber(1)
                .setBlockId(block.getId())
                .build();

        HotStuffMessage decideMessage = HotStuffMessage.newBuilder()
                .setType(HotStuffMessage.Type.DECIDE)
                .setViewNumber(1)
                .setJustify(decideQc)
                .build();

        Method executeDecideIfReady = BasicHotStuffCoordinator.class.getDeclaredMethod(
                "executeDecideIfReady",
                String.class,
                HotStuffMessage.class
        );
        executeDecideIfReady.setAccessible(true);

        int initialHeight = serverContext.getBlockChain().getHeight();
        String initialHash = serverContext.getWorldState().computeStateHash();

        boolean first = (boolean) executeDecideIfReady.invoke(coordinator, "s0", decideMessage);
        boolean second = (boolean) executeDecideIfReady.invoke(coordinator, "s0", decideMessage);

        assertTrue(first);
        assertFalse(second);
        assertEquals(initialHeight + 1, serverContext.getBlockChain().getHeight());
        assertEquals(initialHash, serverContext.getWorldState().computeStateHash());
        assertEquals(1, coordinator.getExecutedBlockIds().size());

        coordinator.stop();
        serverContext.stop();
    }
}