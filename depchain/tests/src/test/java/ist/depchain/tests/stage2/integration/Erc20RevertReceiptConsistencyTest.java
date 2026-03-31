package ist.depchain.tests.stage2.integration;

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
import ist.depchain.core.blockchain.BlockChainBlock;
import ist.depchain.core.blockchain.DepChainWorldState;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

/**
 * Guarantee: all honest replicas produce the same receipts and the same
 * resulting state for the same committed block - including reverting calls.
 *
 * Tests:
 * 1. Successful ERC-20 transfer -> identical receipts, state hashes, block
 * hashes.
 * 2. Reverting ERC-20 call (insufficient token balance) -> identical failed
 * receipts, state hashes, block hashes across all replicas.
 * 3. Mixed block (success + revert) -> identical receipts for both.
 */
class Erc20RevertReceiptConsistencyTest {
    private static final String CONFIG_FILE = "../config/config-dev.json";
    private static final String[] REPLICAS = { "s0", "s1", "s2", "s3" };

    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(100_000);
    private static final BigInteger TOKEN_INITIAL_SUPPLY = new BigInteger("10000000000");

    private ClientContext client1Context;
    private ClientContext client2Context;
    private ClientLibrary client1Library;
    private ClientLibrary client2Library;

    @BeforeEach
    void setup() {
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
            assertNotNull(coord);

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

        client1Context
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client1Address));
        client2Context
                .setNonce(ServerApp.getCoordinator("s0").getServerContext().getWorldState().getNonce(client2Address));

        client1Context.start();
        client2Context.start();
    }

    @AfterEach
    void teardown() {
        if (client1Context != null)
            client1Context.stop();
        if (client2Context != null)
            client2Context.stop();
        stopReplicas();
    }

    /**
     * Client2 has zero tokens and attempts a token transfer.
     * The ERC-20 call reverts. All replicas must produce identical failed
     * receipts, identical state hashes, and identical block hashes.
     */
    @Test
    void revertingErc20TransferProducesIdenticalFailedReceiptsAcrossReplicas() {
        Address client2Address = client2Context.getSelfAddress();
        Address client1Address = client1Context.getSelfAddress();

        // Client2 has zero tokens (only client1/treasury has the initial supply).
        int heightBefore = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight();

        // Submit a transfer of 1 token from client2 -> client1: this must revert.
        client2Library.submitTokenTransfer(client1Address.toHexString(), BigInteger.ONE, GAS_PRICE, GAS_LIMIT);

        // Wait for the reverting tx to be committed across all replicas
        waitForMinBlockHeight(heightBefore + 1, 120_000);

        // Verify identical state hashes
        assertReplicaStateHashesMatch();

        // Verify identical block hashes and receipt contents on the latest block
        assertLatestBlocksEquivalentAcrossReplicas();

        // The latest block should contain a failed receipt
        BlockChainBlock latestBlock = ServerApp.getCoordinator("s0").getServerContext().getBlockChain()
                .getLatestBlock();
        boolean hasFailedReceipt = latestBlock.getReceipts().stream().anyMatch(r -> !r.isSuccess());
        assertTrue(hasFailedReceipt, "Expected a failed receipt in the latest block for the reverting transfer");
    }

    /**
     * Submit a successful transfer followed by a reverting one (client2 tries
     * to transfer tokens it doesn't have). Both transactions should produce
     * identical receipts across all replicas - the success receipt and the
     * failure receipt must both match.
     */
    @Test
    void mixedSuccessAndRevertProduceIdenticalReceiptsAcrossReplicas() {
        Address client1Address = client1Context.getSelfAddress();
        Address client2Address = client2Context.getSelfAddress();

        // Successful: client1 (token holder) transfers 500 tokens to client2
        client1Library.submitTokenTransfer(client2Address.toHexString(), BigInteger.valueOf(500), GAS_PRICE, GAS_LIMIT);

        waitForReplicaTokenState(client1Address, client2Address,
                TOKEN_INITIAL_SUPPLY.subtract(BigInteger.valueOf(500)),
                BigInteger.valueOf(500),
                120_000);

        int heightAfterSuccess = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight();

        // Reverting: client2 tries to transfer more tokens than it has
        client2Library.submitTokenTransfer(client1Address.toHexString(), BigInteger.valueOf(1000), GAS_PRICE,
                GAS_LIMIT);

        // Wait for the reverting tx to land in a block
        waitForMinBlockHeight(heightAfterSuccess + 1, 120_000);

        // Token balances must be unchanged after the revert
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            Address contractAddress = ServerApp.getCoordinator(replicaId).getServerContext().getConfig()
                    .getIstContractAddress();

            assertEquals(TOKEN_INITIAL_SUPPLY.subtract(BigInteger.valueOf(500)),
                    tokenBalanceOf(ws, contractAddress, client1Address),
                    "client1 token balance wrong after revert on " + replicaId);
            assertEquals(BigInteger.valueOf(500),
                    tokenBalanceOf(ws, contractAddress, client2Address),
                    "client2 token balance wrong after revert on " + replicaId);
        }

        assertReplicaStateHashesMatch();
        assertLatestBlocksEquivalentAcrossReplicas();
    }

    /**
     * Reverting decreaseAllowance (decrease below zero) across replicas.
     * Verifies that the failed receipt is identical on all replicas and
     * the allowance is unchanged.
     */
    @Test
    void revertingDecreaseAllowanceProducesConsistentStateAndReceipts() {
        Address owner = client1Context.getSelfAddress();
        Address spender = client2Context.getSelfAddress();
        BigInteger allowanceAmount = BigInteger.valueOf(50);

        // Set up allowance
        client1Library.submitIncreaseAllowance(spender.toHexString(), allowanceAmount, GAS_PRICE, GAS_LIMIT);

        waitForReplicaAllowanceState(owner, spender, allowanceAmount, 120_000);

        int heightBeforeRevert = ServerApp.getCoordinator("s0").getServerContext().getBlockChain().getHeight();

        // Try to decrease by more than the current allowance -> reverts
        client1Library.submitDecreaseAllowance(spender.toHexString(), allowanceAmount.add(BigInteger.ONE), GAS_PRICE,
                GAS_LIMIT);

        waitForMinBlockHeight(heightBeforeRevert + 1, 120_000);

        // Allowance must be unchanged
        for (String replicaId : REPLICAS) {
            DepChainWorldState ws = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState();
            Address contractAddress = ServerApp.getCoordinator(replicaId).getServerContext().getConfig()
                    .getIstContractAddress();

            assertEquals(allowanceAmount, tokenAllowanceOf(ws, contractAddress, owner, spender),
                    "allowance should be unchanged after reverting decrease on " + replicaId);
        }

        assertReplicaStateHashesMatch();
        assertLatestBlocksEquivalentAcrossReplicas();
    }

    // ==================== Helpers ====================

    private static void waitForMinBlockHeight(int minHeight, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allReady = true;
            for (String replicaId : REPLICAS) {
                if (ServerApp.getCoordinator(replicaId).getServerContext().getBlockChain().getHeight() < minHeight) {
                    allReady = false;
                    break;
                }
            }
            if (allReady)
                return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        fail("Not all replicas reached block height " + minHeight + " within " + timeoutMs + "ms");
    }

    private static void waitForReplicaTokenState(Address owner, Address spender,
            BigInteger expectedOwnerToken,
            BigInteger expectedSpenderToken,
            long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
                DepChainWorldState ws = coord.getServerContext().getWorldState();
                Address contractAddress = coord.getServerContext().getConfig().getIstContractAddress();

                if (!tokenBalanceOf(ws, contractAddress, owner).equals(expectedOwnerToken)
                        || !tokenBalanceOf(ws, contractAddress, spender).equals(expectedSpenderToken)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch)
                return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        fail("Token state did not converge within " + timeoutMs + "ms");
    }

    private static void waitForReplicaAllowanceState(Address owner, Address spender,
            BigInteger expectedAllowance,
            long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = true;
            for (String replicaId : REPLICAS) {
                BasicHotStuffCoordinator coord = ServerApp.getCoordinator(replicaId);
                DepChainWorldState ws = coord.getServerContext().getWorldState();
                Address contractAddress = coord.getServerContext().getConfig().getIstContractAddress();

                if (!tokenAllowanceOf(ws, contractAddress, owner, spender).equals(expectedAllowance)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch)
                return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        fail("Allowance state did not converge within " + timeoutMs + "ms");
    }

    private static void assertReplicaStateHashesMatch() {
        String referenceHash = null;
        for (String replicaId : REPLICAS) {
            String stateHash = ServerApp.getCoordinator(replicaId).getServerContext().getWorldState()
                    .computeStateHash();
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
            BlockChainBlock latestBlock = ServerApp.getCoordinator(replicaId).getServerContext().getBlockChain()
                    .getLatestBlock();
            if (referenceBlockHash == null) {
                referenceBlockHash = latestBlock.getBlockHash();
                referenceStateHash = latestBlock.getStateHash();
                referenceReceipts = latestBlock.getReceipts();
                continue;
            }

            assertEquals(referenceBlockHash, latestBlock.getBlockHash(), "Block hash mismatch on " + replicaId);
            assertEquals(referenceStateHash, latestBlock.getStateHash(), "State hash mismatch on " + replicaId);
            assertEquals(referenceReceipts.size(), latestBlock.getReceipts().size(),
                    "Receipt count mismatch on " + replicaId);

            for (int i = 0; i < referenceReceipts.size(); i++) {
                TransactionReceipt expected = referenceReceipts.get(i);
                TransactionReceipt actual = latestBlock.getReceipts().get(i);
                assertEquals(expected.isSuccess(), actual.isSuccess(),
                        "Receipt success mismatch at index " + i + " on " + replicaId);
                assertEquals(expected.getGasUsed(), actual.getGasUsed(),
                        "Receipt gasUsed mismatch at index " + i + " on " + replicaId);
                assertEquals(expected.getFee(), actual.getFee(),
                        "Receipt fee mismatch at index " + i + " on " + replicaId);
                assertEquals(expected.getError(), actual.getError(),
                        "Receipt error mismatch at index " + i + " on " + replicaId);
                assertArrayEquals(expected.getTxHash(), actual.getTxHash(),
                        "Receipt txHash mismatch at index " + i + " on " + replicaId);
                assertArrayEquals(expected.getReturnData(), actual.getReturnData(),
                        "Receipt returnData mismatch at index " + i + " on " + replicaId);
                assertEquals(expected.getContractAddress(), actual.getContractAddress(),
                        "Receipt contractAddress mismatch at index " + i + " on " + replicaId);
            }
        }
    }

    private static BigInteger tokenBalanceOf(DepChainWorldState ws, Address contractAddress, Address owner) {
        return ws.getStorageValue(contractAddress, balanceSlot(owner)).toBigInteger();
    }

    private static BigInteger tokenAllowanceOf(DepChainWorldState ws, Address contractAddress, Address owner,
            Address spender) {
        return ws.getStorageValue(contractAddress, allowanceSlot(owner, spender)).toBigInteger();
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

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[] { CONFIG_FILE, serverId, "false" });
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
            if (coord == null)
                continue;
            try {
                coord.getServerContext().stop();
            } catch (Exception ignored) {
            }
            coord.stop();
        }
    }
}
