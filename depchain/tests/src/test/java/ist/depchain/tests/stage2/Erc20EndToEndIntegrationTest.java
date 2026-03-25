package ist.depchain.tests.stage2;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import ist.depchain.client.ClientContext;
import ist.depchain.client.ClientLibrary;
import ist.depchain.client.MessageHandler;
import ist.depchain.common.utils.Config;
import ist.depchain.common.utils.Erc20Abi;
import ist.depchain.core.ServerApp;
import ist.depchain.core.ServerContext;
import ist.depchain.core.blockchain.EvmService;
import ist.depchain.core.blockchain.TransactionReceipt;
import ist.depchain.core.hotstuff.BasicHotStuffCoordinator;

@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Erc20EndToEndIntegrationTest {

    private static final String CONFIG_FILE = "../config/config-dev.json";

    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final BigInteger GAS_LIMIT = new BigInteger("100000");
    private static final BigInteger SEED_AMOUNT = new BigInteger("1000");

    private static Config clusterConfig;
    private static Address client1Address;
    private static Address client2Address;
    private static Address initialTokenHolder;
    private static Address istContractAddress;

    private ClientContext clientContext1;
    private MessageHandler messageHandler1;
    private ClientLibrary clientLibrary1;

    private ClientContext clientContext2;
    private MessageHandler messageHandler2;
    private ClientLibrary clientLibrary2;

    @BeforeAll
    void setupCluster() throws Exception {
        clusterConfig = Config.loadConfiguration(CONFIG_FILE, "client1");
        if (clusterConfig == null) {
            throw new IllegalStateException("Failed to load config: " + CONFIG_FILE);
        }

        client1Address = Address.fromHexString(clusterConfig.getProcessInfo("client1").getAddress());
        client2Address = Address.fromHexString(clusterConfig.getProcessInfo("client2").getAddress());
        initialTokenHolder = clusterConfig.getInitialTokenHolderAddress();
        istContractAddress = clusterConfig.getIstContractAddress();

        startReplica("s0");
        startReplica("s1");
        startReplica("s2");
        startReplica("s3");

        TimeUnit.SECONDS.sleep(8);

        waitUntilAsserted(() -> {
            for (String serverId : List.of("s0", "s1", "s2", "s3")) {
                assertNotNull(ServerApp.getCoordinator(serverId), "Coordinator missing for " + serverId);
            }
        }, Duration.ofSeconds(10));

        seedClient1TokensOnAllServers(SEED_AMOUNT);

        waitUntilAsserted(() -> {
            for (String serverId : List.of("s0", "s1", "s2", "s3")) {
                assertEquals(SEED_AMOUNT, tokenBalanceOf(serverId, client1Address));
            }
        }, Duration.ofSeconds(5));

        setupClients();
    }

    @AfterAll
    void tearDownAll() {
        if (clientContext1 != null) {
            clientContext1.stop();
        }
        if (clientContext2 != null) {
            clientContext2.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("End-to-end ERC20 transfer commits and updates balances")
    void endToEndErc20TransferCommitsAndUpdatesBalances() throws Exception {
        BigInteger transferAmount = BigInteger.valueOf(300);

        BigInteger client1Before = tokenBalanceOf("s0", client1Address);
        BigInteger client2Before = tokenBalanceOf("s0", client2Address);

        clientLibrary1.submitTokenTransfer(
                istContractAddress.toHexString(),
                client2Address.toHexString(),
                transferAmount,
                GAS_PRICE,
                GAS_LIMIT
        );

        waitUntilAsserted(() -> {
            for (String serverId : List.of("s0", "s1", "s2", "s3")) {
                assertEquals(client1Before.subtract(transferAmount), tokenBalanceOf(serverId, client1Address));
                assertEquals(client2Before.add(transferAmount), tokenBalanceOf(serverId, client2Address));

                List<TransactionReceipt> receipts =
                        getCoordinator(serverId).getServerContext().getBlockChain().getLatestBlock().getReceipts();

                assertFalse(receipts.isEmpty());
                assertTrue(receipts.get(0).isSuccess());
            }
        }, Duration.ofSeconds(10));
    }

    @Test
    @Order(2)
    @DisplayName("End-to-end increaseAllowance then transferFrom commits")
    void endToEndIncreaseAllowanceThenTransferFromCommits() throws Exception {
        BigInteger allowanceAmount = BigInteger.valueOf(400);
        BigInteger transferFromAmount = BigInteger.valueOf(250);

        BigInteger client1Before = tokenBalanceOf("s0", client1Address);
        BigInteger client2Before = tokenBalanceOf("s0", client2Address);

        clientLibrary1.submitIncreaseAllowance(
                istContractAddress.toHexString(),
                client2Address.toHexString(),
                allowanceAmount,
                GAS_PRICE,
                GAS_LIMIT
        );

        waitUntilAsserted(() -> {
            for (String serverId : List.of("s0", "s1", "s2", "s3")) {
                assertEquals(
                        allowanceAmount,
                        tokenAllowanceOf(serverId, client1Address, client2Address)
                );
            }
        }, Duration.ofSeconds(10));

        clientLibrary2.submitTransferFrom(
                istContractAddress.toHexString(),
                client1Address.toHexString(),
                client2Address.toHexString(),
                transferFromAmount,
                GAS_PRICE,
                GAS_LIMIT
        );

        waitUntilAsserted(() -> {
            for (String serverId : List.of("s0", "s1", "s2", "s3")) {
                assertEquals(
                        client1Before.subtract(transferFromAmount),
                        tokenBalanceOf(serverId, client1Address)
                );
                assertEquals(
                        client2Before.add(transferFromAmount),
                        tokenBalanceOf(serverId, client2Address)
                );
                assertEquals(
                        allowanceAmount.subtract(transferFromAmount),
                        tokenAllowanceOf(serverId, client1Address, client2Address)
                );

                List<TransactionReceipt> receipts =
                        getCoordinator(serverId).getServerContext().getBlockChain().getLatestBlock().getReceipts();

                assertFalse(receipts.isEmpty());
                assertTrue(receipts.get(0).isSuccess());
            }
        }, Duration.ofSeconds(10));
    }

    @Test
    @Order(3)
    @DisplayName("End-to-end reverting ERC20 transfer returns committed failure receipt")
    void endToEndRevertingTransferStillReturnsCommittedFailureReceipt() throws Exception {
        BigInteger client2CurrentBalance = tokenBalanceOf("s0", client2Address);
        BigInteger impossibleAmount = client2CurrentBalance.add(BigInteger.ONE);

        clientLibrary2.submitTokenTransfer(
                istContractAddress.toHexString(),
                client1Address.toHexString(),
                impossibleAmount,
                GAS_PRICE,
                GAS_LIMIT
        );

        waitUntilAsserted(() -> {
            for (String serverId : List.of("s0", "s1", "s2", "s3")) {
                List<TransactionReceipt> receipts =
                        getCoordinator(serverId).getServerContext().getBlockChain().getLatestBlock().getReceipts();

                assertFalse(receipts.isEmpty());

                TransactionReceipt receipt = receipts.get(0);
                assertFalse(receipt.isSuccess());
                assertNotNull(receipt.getError());
                assertFalse(receipt.getError().isBlank());
            }
        }, Duration.ofSeconds(10));
    }

    // ----------------------------------------------------
    // Helpers
    // ----------------------------------------------------

    private void setupClients() throws Exception {
        Config config1 = Config.loadConfiguration(CONFIG_FILE, "client1");
        clientContext1 = new ClientContext(config1);
        messageHandler1 = new MessageHandler(clientContext1);
        clientLibrary1 = new ClientLibrary(clientContext1, messageHandler1);
        clientContext1.setNonce(getServerContext("s0").getWorldState().getNonce(client1Address));
        clientContext1.start();

        Config config2 = Config.loadConfiguration(CONFIG_FILE, "client2");
        clientContext2 = new ClientContext(config2);
        messageHandler2 = new MessageHandler(clientContext2);
        clientLibrary2 = new ClientLibrary(clientContext2, messageHandler2);
        clientContext2.setNonce(getServerContext("s0").getWorldState().getNonce(client2Address));
        clientContext2.start();

        TimeUnit.SECONDS.sleep(2);
    }

    private static void startReplica(String serverId) {
        Thread t = new Thread(() -> {
            try {
                ServerApp.main(new String[]{CONFIG_FILE, serverId, "false"});
                System.out.println("[TEST] - Replica " + serverId + " started");
            } catch (Exception e) {
                System.out.println("[TEST] - Error starting replica " + serverId);
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static BasicHotStuffCoordinator getCoordinator(String serverId) {
        BasicHotStuffCoordinator coordinator = ServerApp.getCoordinator(serverId);
        if (coordinator == null) {
            throw new IllegalStateException("Coordinator not found for " + serverId);
        }
        return coordinator;
    }

    private static ServerContext getServerContext(String serverId) {
        return getCoordinator(serverId).getServerContext();
    }

    private static void seedClient1TokensOnAllServers(BigInteger amount) {
        byte[] calldata = Erc20Abi.transfer(client1Address, amount);

        for (String serverId : List.of("s0", "s1", "s2", "s3")) {
            ServerContext context = getServerContext(serverId);
            var worldState = context.getWorldState();
            Bytes runtimeCode = worldState.getCode(istContractAddress);

            EvmService.EvmResult result = EvmService.callContract(
                    worldState,
                    initialTokenHolder,
                    istContractAddress,
                    runtimeCode,
                    calldata
            );

            assertTrue(result.isSuccess(), "Seeding client1 with IST failed on " + serverId
                    + ": " + result.getErrorMessage());

            worldState.registerStorageSlot(istContractAddress, EvmService.erc20BalanceSlot(initialTokenHolder));
            worldState.refreshTrackedStorageValue(istContractAddress, EvmService.erc20BalanceSlot(initialTokenHolder));

            worldState.registerStorageSlot(istContractAddress, EvmService.erc20BalanceSlot(client1Address));
            worldState.refreshTrackedStorageValue(istContractAddress, EvmService.erc20BalanceSlot(client1Address));
        }
    }

    private static BigInteger tokenBalanceOf(String serverId, Address owner) {
        ServerContext context = getServerContext(serverId);
        var serverWorld = context.getWorldState();
        Bytes runtimeCode = serverWorld.getCode(istContractAddress);
        String calldata = "0x" + Erc20Abi.selector("balanceOf(address)") + Erc20Abi.encodeAddress(owner);

        return EvmService.callUint(
                serverWorld,
                owner,
                istContractAddress,
                runtimeCode,
                calldata
        );
    }

    private static BigInteger tokenAllowanceOf(String serverId, Address owner, Address spender) {
        ServerContext context = getServerContext(serverId);
        var serverWorld = context.getWorldState();
        Bytes runtimeCode = serverWorld.getCode(istContractAddress);
        String calldata = "0x" + Erc20Abi.selector("allowance(address,address)")
                + Erc20Abi.encodeAddress(owner)
                + Erc20Abi.encodeAddress(spender);

        return EvmService.callUint(
                serverWorld,
                owner,
                istContractAddress,
                runtimeCode,
                calldata
        );
    }

    private static void waitUntilAsserted(CheckedRunnable assertionBlock, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        AssertionError lastAssertion = null;
        Exception lastException = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                assertionBlock.run();
                return;
            } catch (AssertionError e) {
                lastAssertion = e;
            } catch (Exception e) {
                lastException = e;
            }
            Thread.sleep(200);
        }

        if (lastAssertion != null) {
            throw lastAssertion;
        }
        if (lastException != null) {
            throw lastException;
        }

        fail("Timed out waiting for assertions");
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}