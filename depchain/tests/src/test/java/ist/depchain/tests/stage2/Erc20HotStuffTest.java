package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.utils.Config;
import ist.depchain.core.ServerApp;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

class Erc20HotStuffTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = {"s0", "s1", "s2", "s3"};

    private static final BigInteger QUERY_GAS_PRICE = BigInteger.ONE;
    private static final BigInteger QUERY_GAS_LIMIT = BigInteger.valueOf(100_000);
    private static final BigInteger TOKEN_TRANSFER_AMOUNT = BigInteger.valueOf(5_000);
    private static final BigInteger TOKEN_INITIAL_SUPPLY = new BigInteger("10000000000");
    private static final BigInteger ALLOWANCE_AMOUNT = BigInteger.valueOf(4_000);
    private static final BigInteger TRANSFER_FROM_AMOUNT = BigInteger.valueOf(2_500);
    private static final BigInteger DECREASE_AMOUNT = BigInteger.valueOf(500);
    private static final BigInteger REPLACEMENT_APPROVAL_AMOUNT = BigInteger.valueOf(5_000);

    private ClientContext client1Context;
    private ClientContext client2Context;
    private ClientLibrary client1Library;
    private ClientLibrary client2Library;

    @BeforeEach
    void setup() {
        System.out.println("[TEST] Starting Erc20HotStuffTest");

        for (String replica : REPLICAS) {
            startReplica(replica);
        }

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(15));

        Config client1Config = Config.loadConfiguration(CONFIG_FILE, "client1");
        Config client2Config = Config.loadConfiguration(CONFIG_FILE, "client2");

        client1Context = new ClientContext(client1Config);
        client2Context = new ClientContext(client2Config);
        MessageHandler client1Handler = new MessageHandler(client1Context);
        MessageHandler client2Handler = new MessageHandler(client2Context);
        client1Library = new ClientLibrary(client1Context, client1Handler);
        client2Library = new ClientLibrary(client2Context, client2Handler);

        Address client1Address = client1Context.getSelfAddress();
        Address client2Address = client2Context.getSelfAddress();

        for (String replicaId : REPLICAS) {
            BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
            assertNotNull(coord, "Coordinator for " + replicaId + " should be registered");

            DepChainWorldState ws = coord.getServerContext().getWorldState();
            if (!ws.accountExists(client1Address)) {
                ws.createEOA(client1Address, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(client1Address, BigInteger.valueOf(10_000_000));
            }

            if (!ws.accountExists(client2Address)) {
                ws.createEOA(client2Address, 0, BigInteger.valueOf(10_000_000));
            } else {
                ws.addBalance(client2Address, BigInteger.valueOf(10_000_000));
            }
        }

        long requestBase = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight() * 1000L;
        client1Context.setRequestId((int) requestBase);
        client2Context.setRequestId((int) (requestBase + 500));

        client1Context.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client1Address));
        client2Context.setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client2Address));

        client1Context.start();
        client2Context.start();
    }

    @AfterEach
    void teardown() {
        if (client1Context != null) {
            client1Context.stop();
        }
        if (client2Context != null) {
            client2Context.stop();
        }
        stopReplicas();
        System.out.println("[TEST] Ending Erc20HotStuffTest");
    }

    @Test
    void erc20BalanceQueryAndTransferCommitThroughConsensus() {
        Address client1Address = client1Context.getSelfAddress();
        Address client2Address = client2Context.getSelfAddress();

        client1Library.submitTokenBalanceCheck();

        waitForReplicaState(
                client1Address,
                client2Address,
                TOKEN_INITIAL_SUPPLY,
                BigInteger.ZERO,
                BigInteger.ZERO,
                TimeUnit.SECONDS.toMillis(120)
        );

        client1Library.submitTokenTransfer(client2Address.toHexString(), TOKEN_TRANSFER_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                client1Address,
                client2Address,
                TOKEN_INITIAL_SUPPLY.subtract(TOKEN_TRANSFER_AMOUNT),
                TOKEN_TRANSFER_AMOUNT,
                BigInteger.ZERO,
                TimeUnit.SECONDS.toMillis(120)
        );
    }

    @Test
    void erc20AllowanceLifecycleCommitThroughConsensus() {
        Address owner = client1Context.getSelfAddress();
        Address spender = client2Context.getSelfAddress();

        client1Library.submitIncreaseAllowance(spender.toHexString(), ALLOWANCE_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
            TOKEN_INITIAL_SUPPLY,
            BigInteger.ZERO,
            ALLOWANCE_AMOUNT,
                TimeUnit.SECONDS.toMillis(120)
        );

        client2Library.submitTransferFrom(owner.toHexString(), spender.toHexString(), TRANSFER_FROM_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
            TOKEN_INITIAL_SUPPLY.subtract(TRANSFER_FROM_AMOUNT),
            TRANSFER_FROM_AMOUNT,
            ALLOWANCE_AMOUNT.subtract(TRANSFER_FROM_AMOUNT),
                TimeUnit.SECONDS.toMillis(120)
        );

        client1Library.submitDecreaseAllowance(spender.toHexString(), DECREASE_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY.subtract(TRANSFER_FROM_AMOUNT),
                TRANSFER_FROM_AMOUNT,
                ALLOWANCE_AMOUNT.subtract(TRANSFER_FROM_AMOUNT).subtract(DECREASE_AMOUNT),
                TimeUnit.SECONDS.toMillis(120)
        );
    }

            @Test
            void erc20RevertingAllowanceDecreaseConvergesWithIdenticalStateHashes() {
            Address owner = client1Context.getSelfAddress();
            Address spender = client2Context.getSelfAddress();

            client1Library.submitIncreaseAllowance(spender.toHexString(), ALLOWANCE_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

            waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY,
                BigInteger.ZERO,
                ALLOWANCE_AMOUNT,
                TimeUnit.SECONDS.toMillis(120)
            );

            client1Library.submitDecreaseAllowance(spender.toHexString(), ALLOWANCE_AMOUNT.add(BigInteger.ONE), QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

            waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY,
                BigInteger.ZERO,
                ALLOWANCE_AMOUNT,
                TimeUnit.SECONDS.toMillis(120)
            );

            assertReplicaStateHashesMatch();
            }

            @Test
            void erc20BalanceQueryLeavesStateUnchangedAcrossReplicas() {
            Address owner = client1Context.getSelfAddress();
            Address spender = client2Context.getSelfAddress();

            client1Library.submitTokenBalanceCheck();

            waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY,
                BigInteger.ZERO,
                BigInteger.ZERO,
                TimeUnit.SECONDS.toMillis(120)
            );

            assertReplicaStateHashesMatch();
            }

    @Test
    void unsafeApproveReplacementIsRejectedAndAllowanceStaysAtRemainingAmount() {
        Address owner = client1Context.getSelfAddress();
        Address spender = client2Context.getSelfAddress();

        client1Library.submitIncreaseAllowance(spender.toHexString(), ALLOWANCE_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY,
                BigInteger.ZERO,
                ALLOWANCE_AMOUNT,
                TimeUnit.SECONDS.toMillis(120)
        );

        client2Library.submitTransferFrom(owner.toHexString(), spender.toHexString(), TRANSFER_FROM_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY.subtract(TRANSFER_FROM_AMOUNT),
                TRANSFER_FROM_AMOUNT,
                ALLOWANCE_AMOUNT.subtract(TRANSFER_FROM_AMOUNT),
                TimeUnit.SECONDS.toMillis(120)
        );

        client1Library.submitApprove(spender.toHexString(), REPLACEMENT_APPROVAL_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY.subtract(TRANSFER_FROM_AMOUNT),
                TRANSFER_FROM_AMOUNT,
                ALLOWANCE_AMOUNT.subtract(TRANSFER_FROM_AMOUNT),
                TimeUnit.SECONDS.toMillis(120)
        );
    }

    @Test
    void erc20TransferProducesIdenticalReceiptsAndStateHashesAcrossReplicas() {
        Address client1Address = client1Context.getSelfAddress();
        Address client2Address = client2Context.getSelfAddress();

        client1Library.submitTokenTransfer(client2Address.toHexString(), TOKEN_TRANSFER_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                client1Address,
                client2Address,
                TOKEN_INITIAL_SUPPLY.subtract(TOKEN_TRANSFER_AMOUNT),
                TOKEN_TRANSFER_AMOUNT,
                BigInteger.ZERO,
                TimeUnit.SECONDS.toMillis(120)
        );

        assertLatestBlocksEquivalentAcrossReplicas();
    }

    @Test
    void approvalFrontrunningOrderDoesNotStackAllowance() {
        Address owner = client1Context.getSelfAddress();
        Address spender = client2Context.getSelfAddress();

        client1Library.submitIncreaseAllowance(spender.toHexString(), ALLOWANCE_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY,
                BigInteger.ZERO,
                ALLOWANCE_AMOUNT,
                TimeUnit.SECONDS.toMillis(120)
        );

        client2Library.submitTransferFrom(owner.toHexString(), spender.toHexString(), ALLOWANCE_AMOUNT, QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY.subtract(ALLOWANCE_AMOUNT),
                ALLOWANCE_AMOUNT,
                BigInteger.ZERO,
                TimeUnit.SECONDS.toMillis(120)
        );

        client1Library.submitDecreaseAllowance(spender.toHexString(), BigInteger.valueOf(50), QUERY_GAS_PRICE, QUERY_GAS_LIMIT);

        waitForReplicaState(
                owner,
                spender,
                TOKEN_INITIAL_SUPPLY.subtract(ALLOWANCE_AMOUNT),
                ALLOWANCE_AMOUNT,
                BigInteger.ZERO,
                TimeUnit.SECONDS.toMillis(120)
        );
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

    private static void waitForReplicaState(Address owner,
                                            Address spender,
                                            BigInteger expectedOwnerToken,
                                            BigInteger expectedSpenderToken,
                                            BigInteger expectedAllowance,
                                            long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            String referenceBlockHash = null;

            for (String replicaId : REPLICAS) {
                BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
                DepChainWorldState ws = coord.getServerContext().getWorldState();
                Address contractAddress = coord.getServerContext().getConfig().getIstContractAddress();

                if (!tokenBalanceOf(ws, contractAddress, owner).equals(expectedOwnerToken)
                        || !tokenBalanceOf(ws, contractAddress, spender).equals(expectedSpenderToken)
                        || !tokenAllowanceOf(ws, contractAddress, owner, spender).equals(expectedAllowance)) {
                    allMatch = false;
                    break;
                }

                String blockHash = coord.getServerContext().getBlockChain().getLatestBlock().getBlockHash();
                if (referenceBlockHash == null) {
                    referenceBlockHash = blockHash;
                } else if (!referenceBlockHash.equals(blockHash)) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                return;
            }

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }

        DepChainWorldState ws = ServerApp.getCoordinator("s0").getServerContext().getWorldState();
        fail("ERC20 state did not converge within " + timeoutMs + "ms; ownerNative=" + ws.getBalance(owner)
                + ", spenderNative=" + ws.getBalance(spender));
    }

    private static BigInteger tokenBalanceOf(DepChainWorldState ws, Address contractAddress, Address owner) {
        return ws.getStorageValue(contractAddress, balanceSlot(owner)).toBigInteger();
    }

    private static BigInteger tokenAllowanceOf(DepChainWorldState ws, Address contractAddress, Address owner, Address spender) {
        return ws.getStorageValue(contractAddress, allowanceSlot(owner, spender)).toBigInteger();
    }

    private static void assertReplicaStateHashesMatch() {
        String referenceHash = null;
        for (String replicaId : REPLICAS) {
            String stateHash = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState().computeStateHash();
            if (referenceHash == null) {
                referenceHash = stateHash;
            } else {
                assertEquals(referenceHash, stateHash, "State hash mismatch on " + replicaId);
            }
        }
    }

    private static void assertLatestBlocksEquivalentAcrossReplicas() {
        String referenceBlockHash = null;
        String referenceStateHash = null;
        List<TransactionReceipt> referenceReceipts = null;

        for (String replicaId : REPLICAS) {
            BlockChainBlock latestBlock = ServerApp.getCoordinator(replicaId).getServerContext().getBlockChain().getLatestBlock();
            if (referenceBlockHash == null) {
                referenceBlockHash = latestBlock.getBlockHash();
                referenceStateHash = latestBlock.getStateHash();
                referenceReceipts = latestBlock.getReceipts();
                continue;
            }

            assertEquals(referenceBlockHash, latestBlock.getBlockHash(), "Block hash mismatch on " + replicaId);
            assertEquals(referenceStateHash, latestBlock.getStateHash(), "State hash mismatch on " + replicaId);
            assertEquals(referenceReceipts.size(), latestBlock.getReceipts().size(), "Receipt count mismatch on " + replicaId);

            for (int i = 0; i < referenceReceipts.size(); i++) {
                TransactionReceipt expected = referenceReceipts.get(i);
                TransactionReceipt actual = latestBlock.getReceipts().get(i);
                assertEquals(expected.isSuccess(), actual.isSuccess(), "Receipt success mismatch on " + replicaId);
                assertEquals(expected.getGasUsed(), actual.getGasUsed(), "Receipt gasUsed mismatch on " + replicaId);
                assertEquals(expected.getFee(), actual.getFee(), "Receipt fee mismatch on " + replicaId);
                assertEquals(expected.getError(), actual.getError(), "Receipt error mismatch on " + replicaId);
                assertArrayEquals(expected.getTxHash(), actual.getTxHash(), "Receipt txHash mismatch on " + replicaId);
                assertArrayEquals(expected.getReturnData(), actual.getReturnData(), "Receipt returnData mismatch on " + replicaId);
                assertEquals(expected.getContractAddress(), actual.getContractAddress(), "Receipt contractAddress mismatch on " + replicaId);
            }
        }
    }

    private static UInt256 balanceSlot(Address owner) {
        return UInt256.fromBytes(Bytes.wrap(Hash.sha3(concat(padAddress(owner), padWord(BigInteger.ZERO)))));
    }

    private static UInt256 allowanceSlot(Address owner, Address spender) {
        byte[] outer = Hash.sha3(concat(padAddress(owner), padWord(BigInteger.ONE)));
        return UInt256.fromBytes(Bytes.wrap(Hash.sha3(concat(padAddress(spender), outer))));
    }

    private static byte[] padAddress(Address address) {
        byte[] out = new byte[32];
        byte[] raw = address.toArrayUnsafe();
        System.arraycopy(raw, 0, out, 12, raw.length);
        return out;
    }

    private static byte[] padWord(BigInteger value) {
        byte[] raw = value == null ? new byte[0] : value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            raw = trimmed;
        }

        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

}