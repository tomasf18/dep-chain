package ist.depchain.tests.stage2;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress test: two clients fire dozens of native transfers at each other
 * concurrently through full HotStuff consensus.
 *
 * Uses config-dev.json (no fault injection, no delays) for maximum throughput.
 * Each client runs on its own thread since submitNativeTransfer is blocking.
 *
 * Invariant checked at the end:
 *   total balance of (client1 + client2) = 2 * INITIAL_BALANCE
 *                                          - total gas fees paid
 *   i.e. no coins are created or destroyed.
 */
class StressTest {

    private static final String CONFIG_FILE   = "../config/config-dev.json";
    private static final String[] REPLICAS    = {"s0", "s1", "s2", "s3"};

    private static final String CLIENT1_ADDR = "0xfe37d77266b312ca364bd3f9386e1df4d193e9d9";
    private static final String CLIENT2_ADDR = "0x172bf398d2a931323199521625f471fb1c28879a";

    private static final BigInteger INITIAL_BALANCE = BigInteger.valueOf(10_000_000);
    private static final BigInteger TX_VALUE        = BigInteger.valueOf(100);
    private static final BigInteger GAS_PRICE       = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT       = Stage2GasConstants.NATIVE_TRANSFER_GAS_COST;
    private static final int       NUM_TXS_EACH     = 20; // 40 txs total

    private ClientContext  ctx1, ctx2;
    private MessageHandler msgHandler1, msgHandler2;
    private ClientLibrary  lib1, lib2;

    @BeforeEach
    void setup() {
        for (String id : REPLICAS) startReplica(id);

        System.out.println("[STRESS] Waiting for replica handshake...");
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        ctx1 = new ClientContext(Config.loadConfiguration(CONFIG_FILE, "client1"));
        ctx2 = new ClientContext(Config.loadConfiguration(CONFIG_FILE, "client2"));
        msgHandler1 = new MessageHandler(ctx1);
        msgHandler2 = new MessageHandler(ctx2);
        lib1 = new ClientLibrary(ctx1, msgHandler1);
        lib2 = new ClientLibrary(ctx2, msgHandler2);

        Address addr1 = Address.fromHexString(CLIENT1_ADDR);
        Address addr2 = Address.fromHexString(CLIENT2_ADDR);

        for (String id : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(id);
            assertNotNull(coord, "Coordinator missing for " + id);
            DepChainWorldState ws = coord.getServerContext().getWorldState();

            for (Address addr : new Address[]{addr1, addr2}) {
                if (!ws.accountExists(addr)) ws.createEOA(addr, 0, INITIAL_BALANCE);
                else ws.addBalance(addr, INITIAL_BALANCE);
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        ctx1.setRequestId((int) requestBase);
        ctx2.setRequestId((int) requestBase);
        DepChainWorldState snap = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        ctx1.setNonce(snap.getNonce(addr1));
        ctx2.setNonce(snap.getNonce(addr2));

        ctx1.start();
        ctx2.start();
    }

    @AfterEach
    void teardown() {
        if (ctx1 != null) {
            ctx1.stop();
        }
        if (ctx2 != null) {
            ctx2.stop();
        }
        stopReplicas();
        System.out.println("[STRESS] Ending StressTest");
    }

    @Test
    @DisplayName("Two clients exchange " + NUM_TXS_EACH + " txs each concurrently")
    void testConcurrentTransfers() throws InterruptedException {
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));

        // Snapshot actual balances now — world state may include genesis funds
        DepChainWorldState wsSnap = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        Address addr1 = Address.fromHexString(CLIENT1_ADDR);
        Address addr2 = Address.fromHexString(CLIENT2_ADDR);
        BigInteger initialBal1 = wsSnap.getBalance(addr1);
        BigInteger initialBal2 = wsSnap.getBalance(addr2);
        BigInteger initialTotal = initialBal1.add(initialBal2);
        System.out.printf("[STRESS] initial balance: client1=%s  client2=%s  total=%s%n",
                initialBal1, initialBal2, initialTotal);

        AtomicReference<Throwable> err1 = new AtomicReference<>();
        AtomicReference<Throwable> err2 = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                for (int i = 0; i < NUM_TXS_EACH; i++) {
                    lib1.submitNativeTransfer(CLIENT2_ADDR, TX_VALUE, GAS_PRICE, GAS_LIMIT);
                    System.out.printf("[STRESS] client1 tx %d/%d accepted%n", i + 1, NUM_TXS_EACH);
                }
            } catch (Throwable e) {
                err1.set(e);
            }
        }, "client1-thread");

        Thread t2 = new Thread(() -> {
            try {
                for (int i = 0; i < NUM_TXS_EACH; i++) {
                    lib2.submitNativeTransfer(CLIENT1_ADDR, TX_VALUE, GAS_PRICE, GAS_LIMIT);
                    System.out.printf("[STRESS] client2 tx %d/%d accepted%n", i + 1, NUM_TXS_EACH);
                }
            } catch (Throwable e) {
                err2.set(e);
            }
        }, "client2-thread");

        long timeoutMs = NUM_TXS_EACH * 70_000L; // 70s per tx worst case
        t1.start();
        t2.start();
        t1.join(timeoutMs);
        t2.join(timeoutMs);

        assertFalse(t1.isAlive(), "client1 thread did not finish in time");
        assertFalse(t2.isAlive(), "client2 thread did not finish in time");
        assertNull(err1.get(), "client1 error: " + err1.get());
        assertNull(err2.get(), "client2 error: " + err2.get());

        // Conservation: total balance = initialTotal - all gas fees paid
        BigInteger maxGasPerTx = GAS_PRICE.multiply(GAS_LIMIT);
        BigInteger totalGasPaid = maxGasPerTx.multiply(BigInteger.valueOf(NUM_TXS_EACH * 2L));
        BigInteger minExpectedTotal = initialTotal.subtract(totalGasPaid);

        waitForBalanceRange(addr1, addr2, minExpectedTotal, initialTotal, TimeUnit.SECONDS.toMillis(120));

        DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        BigInteger bal1 = ws.getBalance(addr1);
        BigInteger bal2 = ws.getBalance(addr2);
        BigInteger totalBalance = bal1.add(bal2);

        System.out.printf("[STRESS] client1 balance: %s%n", bal1);
        System.out.printf("[STRESS] client2 balance: %s%n", bal2);
        System.out.printf("[STRESS] total balance:   %s (min expected: %s)%n", totalBalance, minExpectedTotal);

        assertTrue(totalBalance.compareTo(minExpectedTotal) >= 0,
                "Total balance fell below minimum expected (coins were lost)");
        assertTrue(totalBalance.compareTo(initialTotal) <= 0,
                "Total balance exceeds initial total (coins were created)");
    }

    private static void startReplica(String id) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, id, "false"});
            } catch (Exception e) {
                System.err.println("[STRESS] Error starting replica " + id);
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
                System.err.println("[STRESS] Error stopping replica context " + replicaId + ": " + e.getMessage());
            }
            coord.stop();
        }
    }

    private static void waitForBalanceRange(Address addr1, Address addr2, BigInteger minExpectedTotal,
                                             BigInteger maxExpectedTotal, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int stableReads = 0;

        while (System.currentTimeMillis() < deadline) {
            DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
            BigInteger totalBalance = ws.getBalance(addr1).add(ws.getBalance(addr2));
            if (totalBalance.compareTo(minExpectedTotal) >= 0 && totalBalance.compareTo(maxExpectedTotal) <= 0) {
                stableReads++;
                if (stableReads >= 3) {
                    System.out.printf("[STRESS] client balance total stabilized within expected range: %s..%s%n",
                            minExpectedTotal, maxExpectedTotal);
                    return;
                }
            } else {
                stableReads = 0;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }

        DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        BigInteger totalBalance = ws.getBalance(addr1).add(ws.getBalance(addr2));
        fail("Client balance total did not stabilize within expected range [" + minExpectedTotal + ", "
                + maxExpectedTotal + "] within " + timeoutMs + "ms; actual=" + totalBalance);
    }
}
