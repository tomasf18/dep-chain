package ist.depchain.client;

import com.google.protobuf.ByteString;

import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.hyperledger.besu.datatypes.Address;
import org.web3j.utils.Numeric;

import ist.depchain.common.ApplicationMessage;
import ist.depchain.common.ClientRequest;
import ist.depchain.common.Transaction;
import ist.depchain.common.utils.Crypto;
import ist.depchain.common.utils.Erc20Abi;
import ist.depchain.common.utils.TransactionSigner;

public class ClientLibrary {
    private final ClientContext clientContext;
    private final MessageHandler messageHandler;

    // Timeout for ACCEPTED status (much shorter than commit timeout)
    private static final long SUBMIT_TIMEOUT_SECONDS = 10;

    // For read operations, we can use fixed gas price and limit since they don't actually consume gas, but we want to include them in the 
    // blockchain to ensure they see a consistent view of the state with respect to pending transactions.
    private static final BigInteger QUERY_GAS_PRICE = BigInteger.valueOf(1);
    private static final BigInteger QUERY_GAS_LIMIT = BigInteger.valueOf(100_000);

    public ClientLibrary(ClientContext clientContext, MessageHandler messageHandler) {
        this.clientContext = clientContext;
        this.messageHandler = messageHandler;
    }

    public void submitNativeBalanceCheck() {
        Address selfAddress = clientContext.getSelfAddress();
        String requestDescription = "native.balanceOf(" + selfAddress.toHexString() + ")";
        long nonce = clientContext.getNonce();

        Transaction unsignedTx = Transaction.nativeBalanceQuery(clientContext.getSelfAddress(), selfAddress, QUERY_GAS_PRICE, QUERY_GAS_LIMIT, nonce, null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(), clientContext.getConfig().getSignatureAlgorithm());

        int reqId = clientContext.getRequestId().incrementAndGet();
        submitSignedTransaction(reqId, signedTx, requestDescription);

        System.out.println("[SUBMITTED] native balance tx reqId=" + reqId + " nonce=" + nonce + " target=" + selfAddress.toHexString());
    }

    public void submitTokenBalanceCheck() {
        Address selfAddress = clientContext.getSelfAddress();
        byte[] calldata = Erc20Abi.balanceOf(selfAddress);
        submitContractCall(calldata, QUERY_GAS_PRICE, QUERY_GAS_LIMIT, "erc20.balanceOf(" + selfAddress.toHexString() + ")");
    }
        

    public void submitNativeTransfer(String toHex, BigInteger value, BigInteger gasPrice, BigInteger gasLimit) {
        Address to = Address.fromHexString(toHex);
        long nonce = clientContext.getNonce();

        Transaction unsignedTx = new Transaction(clientContext.getSelfAddress(), to, value, new byte[0], gasPrice, gasLimit, nonce, null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(), clientContext.getConfig().getSignatureAlgorithm());

        int reqId = clientContext.getRequestId().incrementAndGet();
        String requestDescription = "native:" + signedTx.getFrom() + ":" + toHex + ":" + value + ":" + nonce;

        submitSignedTransaction(reqId, signedTx, requestDescription);

        System.out.println("[SUBMITTED] native tx reqId=" + reqId + " nonce=" + nonce);
    }

    public void submitTokenTransfer(String toHex, BigInteger amount, BigInteger gasPrice, BigInteger gasLimit) {
        Address to = Address.fromHexString(toHex);
        byte[] calldata = Erc20Abi.transfer(to, amount);
        submitContractCall(calldata, gasPrice, gasLimit, "erc20.transfer(" + toHex + "," + amount + ")");
    }

    public void submitIncreaseAllowance(String spenderHex, BigInteger amount, BigInteger gasPrice, BigInteger gasLimit) {
        Address spender = Address.fromHexString(spenderHex);
        byte[] calldata = Erc20Abi.increaseAllowance(spender, amount);
        submitContractCall(calldata, gasPrice, gasLimit, "erc20.increaseAllowance(" + spenderHex + "," + amount + ")");
    }

    public void submitApprove(String spenderHex, BigInteger amount, BigInteger gasPrice, BigInteger gasLimit) {
        Address spender = Address.fromHexString(spenderHex);
        byte[] calldata = Erc20Abi.approve(spender, amount);
        submitContractCall(calldata, gasPrice, gasLimit, "erc20.approve(" + spenderHex + "," + amount + ")");
    }

    public void submitDecreaseAllowance(String spenderHex, BigInteger amount, BigInteger gasPrice, BigInteger gasLimit) {
        Address spender = Address.fromHexString(spenderHex);
        byte[] calldata = Erc20Abi.decreaseAllowance(spender, amount);
        submitContractCall(calldata, gasPrice, gasLimit, "erc20.decreaseAllowance(" + spenderHex + "," + amount + ")");
    }

    public void submitTransferFrom(String fromHex, String toHex, BigInteger amount, BigInteger gasPrice, BigInteger gasLimit) {
        Address from = Address.fromHexString(fromHex);
        Address to = Address.fromHexString(toHex);
        byte[] calldata = Erc20Abi.transferFrom(from, to, amount);
        submitContractCall(calldata, gasPrice, gasLimit, "erc20.transferFrom(" + fromHex + "," + toHex + "," + amount + ")");
    }

    private void submitContractCall(byte[] calldata, BigInteger gasPrice, BigInteger gasLimit, String description) {
        Address contractAddress = clientContext.getConfig().getIstContractAddress();
        long nonce = clientContext.getNonce();

        Transaction unsignedTx = new Transaction(clientContext.getSelfAddress(), contractAddress, BigInteger.ZERO, calldata, gasPrice, gasLimit, nonce, null);

        Transaction signedTx = TransactionSigner.sign(unsignedTx, clientContext.getPrivateKey(), clientContext.getConfig().getSignatureAlgorithm());

        int reqId = clientContext.getRequestId().incrementAndGet();
        String desc = description + " nonce=" + nonce;

        submitSignedTransaction(reqId, signedTx, desc);

        System.out.println("[SUBMITTED] contract tx reqId=" + reqId + " nonce=" + nonce + " to=" + contractAddress.toHexString() + " data=0x" + Numeric.toHexStringNoPrefix(calldata));
    }

    private void submitSignedTransaction(int reqId, Transaction signedTx, String requestDescription) {
        ClientRequest unsignedReq = ClientRequest.newBuilder()
                .setClientId(clientContext.getConfig().getSelfId())
                .setRequestId(reqId)
                .setTransaction(signedTx.toProto())
                .build();

        ClientRequest signedReq = signRequest(unsignedReq);

        ApplicationMessage appMsg = ApplicationMessage.newBuilder()
                .setClientRequest(signedReq)
                .build();

        messageHandler.getPendingRequests().put(reqId, new ConcurrentHashMap<>());
        clientContext.registerRequestInMap(reqId, requestDescription);

        CompletableFuture<Void> committed = messageHandler.registerFuture(reqId);

        Set<String> destinations = clientContext.getConfig().getBlockChainServers().keySet();
        clientContext.getAuthenticatedPerfectLink().broadcast(destinations, appMsg.toByteArray());

        System.out.println("[SENT] reqId=" + reqId + " from=" + signedTx.getFrom() + " to=" + signedTx.getTo() + " value=" + signedTx.getValue() + " nonce=" + signedTx.getNonce());

        // Increment nonce immediately after broadcast so next transaction gets unique nonce
        clientContext.incrementNonce();

        try {
            // Wait only for ACCEPTED status (transaction is being processed)
            committed.get(SUBMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.println("[ACCEPTED] reqId=" + reqId + " nonce=" + signedTx.getNonce() + " - transaction is now being processed");
        } catch (TimeoutException e) {
            throw new RuntimeException("Transaction failed to receive acceptance from network (reqId=" + reqId + ", nonce=" + signedTx.getNonce() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for transaction acceptance (reqId=" + reqId + ")", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause().getMessage(), e.getCause());
        }
    }

    private ClientRequest signRequest(ClientRequest unsigned) {
        try {
            byte[] sig = Crypto.sign(
                    unsigned.toBuilder().clearSignature().build().toByteArray(),
                    clientContext.getPrivateKey(),
                    clientContext.getConfig().getSignatureAlgorithm());

            return ClientRequest.newBuilder(unsigned)
                    .setSignature(ByteString.copyFrom(sig))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign client request", e);
        }
    }
}