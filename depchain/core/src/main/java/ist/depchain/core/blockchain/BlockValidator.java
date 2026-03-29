package ist.depchain.core.blockchain;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

import ist.depchain.common.Block;
import ist.depchain.common.ClientRequestMeta;
import ist.depchain.common.Transaction;
import ist.depchain.common.TransactionPayload;
import ist.depchain.common.utils.Config;
import ist.depchain.network.crypto.KeyLoader;

/**
 * Validates a proposed HotStuff block before a replica votes PREPARE.
 *
 * Rules:
 * - tx count must match metadata count
 * - every tx is re-validated structurally (signature, signer/address, nonce, gas)
 * - semantic success is deferred to deterministic execution after consensus
 * - tx failures caused by execution (e.g. out-of-gas) are allowed, as long as the tx
 *   is otherwise valid and deterministic; they still mutate nonce/balance on the snapshot
 */
public final class BlockValidator {

    private BlockValidator() {}

    public static ValidationResult validateProposedBlock(Block protoBlock, DepChainWorldState committedState, Config config) {

        List<TransactionPayload> txPayloads = protoBlock.getTransactionsList();
        List<ClientRequestMeta> metaList = protoBlock.getRequestMetaList();

        if (txPayloads.size() != metaList.size()) {
            return ValidationResult.fail("transactions/requestMeta size mismatch");
        }

        DepChainWorldState snapshot = committedState.copy();

        for (int i = 0; i < txPayloads.size(); i++) {
            Transaction tx = Transaction.fromProto(txPayloads.get(i));
            ClientRequestMeta meta = metaList.get(i);

            String clientId = meta.getClientId();
            String keyPath = config.getTrustedProcessKeyPathString(clientId);
            PublicKey clientPublicKey = KeyLoader.loadPublicKey(keyPath);
            if (clientPublicKey == null) {
                return ValidationResult.fail("missing public key for client " + clientId);
            }

            ValidationResult txResult = TransactionValidator.validate(tx, clientPublicKey, config.getSignatureAlgorithm(), snapshot, null);

            if (!txResult.isValid()) {
                return ValidationResult.fail("invalid tx at index " + i + ": " + txResult.getErrorMessage());
            }

            // Treat each accepted transaction as consuming the sender's current nonce
            // so a malicious leader cannot pack two transactions with the same nonce
            // into the same proposed block.
            snapshot.incrementNonce(tx.getFrom());
        }

        return ValidationResult.ok();
    }

    public static List<Transaction> decodeTransactionsFromProto(Block protoBlock) {
        List<Transaction> out = new ArrayList<>();
        for (TransactionPayload payload : protoBlock.getTransactionsList()) {
            out.add(Transaction.fromProto(payload));
        }
        return out;
    }
}