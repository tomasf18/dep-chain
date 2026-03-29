package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.google.protobuf.ByteString;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ist.depchain.common.HotStuffMessage;
import ist.depchain.common.QC;
import ist.depchain.core.ServerApp;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

class ByzantineDivergentQcInjectionTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    @BeforeEach
    void setup() {
        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));
    }

    @AfterEach
    void teardown() {
        stopReplicas();
    }

    @Test
    void honestReplicasRejectDivergentForgedQcInjection() {
        BasicHotStuffCoordinator coordinatorS0 = ServerApp.getCoordinator("s0");
        BasicHotStuffCoordinator coordinatorS1 = ServerApp.getCoordinator("s1");
        BasicHotStuffCoordinator coordinatorS2 = ServerApp.getCoordinator("s2");
        BasicHotStuffCoordinator coordinatorS3 = ServerApp.getCoordinator("s3");

        assertNotNull(coordinatorS0);
        assertNotNull(coordinatorS1);
        assertNotNull(coordinatorS2);
        assertNotNull(coordinatorS3);

        String leaderId = coordinatorS0.getLeaderForView(1);

        QC forgedQcA = forgedCommitJustify("fork-a");
        QC forgedQcB = forgedCommitJustify("fork-b");

        HotStuffMessage commitA = HotStuffMessage.newBuilder()
                .setType(HotStuffMessage.Type.COMMIT)
                .setViewNumber(1)
                .setJustify(forgedQcA)
                .build();

        HotStuffMessage commitB = HotStuffMessage.newBuilder()
                .setType(HotStuffMessage.Type.COMMIT)
                .setViewNumber(1)
                .setJustify(forgedQcB)
                .build();

        coordinatorS0.processMessage(leaderId, commitA);
        coordinatorS2.processMessage(leaderId, commitB);

        assertGenesisLockedQc(coordinatorS0);
        assertGenesisLockedQc(coordinatorS1);
        assertGenesisLockedQc(coordinatorS2);
        assertGenesisLockedQc(coordinatorS3);

        assertTrue(coordinatorS0.getExecutedBlockIds().isEmpty(), "forged QC injection must not execute a block on s0");
        assertTrue(coordinatorS2.getExecutedBlockIds().isEmpty(), "forged QC injection must not execute a block on s2");
    }

    private static QC forgedCommitJustify(String label) {
        byte[] forgedSig = UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8)).toString().getBytes(StandardCharsets.UTF_8);
        return QC.newBuilder()
                .setType(HotStuffMessage.Type.PRE_COMMIT)
                .setViewNumber(1)
                .setBlockId(ByteString.copyFromUtf8(label + "-" + UUID.randomUUID()))
                .setThresholdSig(ByteString.copyFrom(forgedSig))
                .build();
    }

    private static void assertGenesisLockedQc(BasicHotStuffCoordinator coordinator) {
        assertEquals(0, coordinator.getLockedQC().getViewNumber(), "locked QC view must remain at genesis");
        assertTrue(coordinator.getLockedQC().getBlockId().isEmpty(), "locked QC block must remain genesis");
    }

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, serverId, "false"});
            } catch (Exception e) {
                System.err.println("[TEST] Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void stopReplicas() {
        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            if (coord == null) {
                continue;
            }

            try {
                coord.getServerContext().stop();
            } catch (Exception e) {
                System.err.println("[TEST] Error stopping replica context " + replicaId + ": " + e.getMessage());
            }
            coord.stop();
        }
    }
}