# Stage 2 Test Reference

Quick reference for every test currently implemented under `tests/src/test/java/ist/depchain/tests/stage2`.

## BlockBuildingAndPersistenceTest
- blockBuilderOrdersByFeeDescending - Verifies higher-fee transactions are ordered first in a block.
- blockBuilderBreaksTiesByTxHash - Verifies equal-fee transactions are ordered deterministically by transaction hash.
- orderingIsDeterministicRegardlessOfInputOrder - Verifies block ordering is stable no matter how the input list is arranged.
- blockHashIsDeterministic - Verifies the same inputs produce the same block hash.
- differentTransactionsProduceDifferentHashes - Verifies different transactions change the block hash.
- differentProposersProduceDifferentHashes - Verifies the proposer address affects the block hash.
- blockNumberIncrementsFromParent - Verifies child blocks increment the block number from the parent.
- blockPointsToParentHash - Verifies a block stores the parent hash correctly.
- proposerIsStoredInBlock - Verifies the proposer address is stored in the block metadata.
- finalizeAttachesReceipts - Verifies finalization attaches receipts without changing the block hash.
- serializeAndDeserializeBlock - Verifies full block JSON round-trip preserves transactions, receipts, and metadata.
- serializeGenesisBlockWithNulls - Verifies genesis-style blocks serialize and deserialize with null parent/proposer values.
- blockChainPersistsBlocksToDisk - Verifies blocks are written to disk and can be read back successfully.
- blockChainWithoutPersistenceDoesNotCrash - Verifies the blockchain works when no persistence directory is configured.

## BlockSerializerReceiptRoundTripTest
- serializerPreservesReturnDataAndContractAddress - Verifies receipt return data and contract address survive block serialization.

## BlockValidationAndExecutionTest
- executorDoesNotMutateCommittedStateWhenUsingSnapshot - Verifies execution against a working copy does not mutate committed state.
- blockChainRejectsBrokenParentLink - Verifies the chain rejects blocks with an invalid parent hash.
- stateHashChangesWhenBalancesChange - Verifies the world-state hash changes after balance updates.

## ClientResponseReceiptFieldsTest
- clientResponsePreservesExtendedReceiptFields - Verifies client responses preserve all extended receipt fields during serialization.
- clientResponseAllowsEmptyOptionalReceiptFields - Verifies optional receipt fields can be omitted and remain empty after parsing.

## ConsensusIntegrationTest
- drainBatchReturnsUpToMaxSize - Verifies mempool batching respects the requested maximum size.
- drainBatchReturnsAllWhenLessThanMax - Verifies batching returns all available requests when fewer than the limit exist.
- drainBatchReturnsEmptyListWhenMempoolEmpty - Verifies draining an empty mempool returns an empty list.
- drainBatchPreservesFIFOOrder - Verifies mempool draining preserves insertion order.
- drainBatchRemovesDedupKeys - Verifies draining removes deduplication keys so the same request can be enqueued again.
- transactionProtoRoundTrip - Verifies a native transaction survives protobuf conversion and back.
- contractDeploymentProtoRoundTrip - Verifies contract-deployment transactions survive protobuf conversion and back.
- fullPipelineSingleTransaction - Verifies one transaction goes through build, execute, and state update correctly.
- fullPipelineMultipleTransactionsDeterministicOrdering - Verifies multiple transactions are ordered by fee and executed in that order.
- fullPipelineWithBlockFinalization - Verifies block finalization attaches receipts and preserves the block hash.
- blockPersistenceAfterExecution - Verifies finalized blocks can be added to the chain and persisted in sequence.
- receiptMappingByTxHashAfterReorder - Verifies receipts can be matched back to the original client requests after block reordering.
- emptyBlockBuildsSuccessfully - Verifies an empty block still builds with valid metadata.
- failedTransactionStillIncludedInBlock - Verifies failed executions still produce receipts and remain in the finalized block.
- proposerReceivesGasFees - Verifies the proposer is credited with gas fees from successful transactions.
- consecutiveBlocksBuildCorrectChain - Verifies sequential finalized blocks link together correctly.
- multipleTransactionsGasFeesAllGoToProposer - Verifies gas fees from multiple transactions accumulate on the proposer.
- transactionsFailWhenBalanceExhaustedMidBlock - Verifies later transactions fail when earlier ones exhaust the sender balance.

## Erc20AbiTest
- smartContractMethodIdentifierProducesExpectedLength - Verifies ABI method selectors are 8 hex characters long.
- encodeAddressIs32BytesLeftPadded - Verifies addresses are encoded as 32-byte left-padded values.
- encodeUint256Is32BytesLeftPadded - Verifies uint256 values are encoded as 32-byte left-padded values.
- transferCalldataHasCorrectShape - Verifies ERC-20 transfer calldata has the expected selector and arguments.
- increaseAllowanceCalldataHasCorrectShape - Verifies increaseAllowance calldata is formed correctly.
- decreaseAllowanceCalldataHasCorrectShape - Verifies decreaseAllowance calldata is formed correctly.
- allowanceCalldataHasCorrectShape - Verifies allowance calldata is formed correctly.
- transferFromCalldataHasCorrectShape - Verifies transferFrom calldata is formed correctly.
- balanceOfCalldataHasCorrectShape - Verifies balanceOf calldata is formed correctly.

## NativeTransferHotStuffTest
- testMultipleSequentialTransfers - Verifies three sequential native transfers commit through HotStuff and converge to the expected replica state.

## StressTest
- testConcurrentTransfers - Verifies concurrent native transfers complete successfully and preserve total balance conservation under load.

## TransactionExecutionTest
- successfulNativeTransfer - Verifies a basic native transfer succeeds and charges the correct gas fee.
- nonceIncrementsOnEachExecution - Verifies nonce increases after each successful execution.
- refundsUnusedGasWhenGasLimitExceedsGasUsed - Verifies unused gas is refunded when the gas limit exceeds actual usage.
- outOfGasAbortsTransferButChargesFullGasLimit - Verifies out-of-gas execution fails, charges the full limit, and refunds the value transfer.
- insufficientBalanceForUpfrontCostFails - Verifies transactions fail when the sender cannot afford value plus upfront gas.
- transferToNonExistentAccountCreatesIt - Verifies sending to a new address creates the account.
- zeroValueTransferStillChargesGas - Verifies zero-value transfers still consume gas.
- higherGasPriceIncreaseFee - Verifies higher gas price increases the fee and proposer credit.
- proposerAccumulatesFees - Verifies proposer balances accumulate across multiple transactions.
- nullProposerBurnsFees - Verifies fees are burned when no proposer is credited.

## TransactionExecutorErc20Test
- erc20TransferUpdatesTokenBalancesAndChargesNativeFee - Verifies ERC-20 transfers update token balances and still charge native gas.
- increaseAllowanceThenTransferFromWorks - Verifies allowance flow works across transfer, increaseAllowance, and transferFrom.
- contractCallRevertProducesFailedReceipt - Verifies failed ERC-20 contract calls produce a failed receipt but still charge gas.

## TransactionValidationTest
- acceptsValidTransaction - Verifies a well-formed signed transaction passes validation.
- rejectsWrongNonce - Verifies validation rejects transactions with an unexpected nonce.
- rejectsInsufficientBalance - Verifies validation rejects transactions when the sender lacks funds.
- rejectsSignerSenderMismatch - Verifies validation rejects signatures that do not match the sender.
- rejectsZeroGasPrice - Verifies validation rejects a zero gas price.
- rejectsZeroGasLimit - Verifies validation rejects a zero gas limit.
- rejectsNegativeValue - Verifies validation rejects a negative transfer value.