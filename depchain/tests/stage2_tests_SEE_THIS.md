# Stage 2 Test Reference

This is a complete index of the files under `tests/src/test/java/ist/depchain/tests/stage2`.
Tests are grouped into `integration/` and `unit/` folders.
It is meant as a quick orientation guide.

## Shared Test Utility

### Stage2GasConstants
- `NATIVE_TRANSFER_GAS_COST` - Shared constant for the fixed gas cost of a native transfer in Stage 2 tests. It keeps gas-related assertions consistent across the suite.

## Block Building, Ordering, and Persistence

### BlockBuilderOrderingTest
- `singleSender_orderedByNonceNotFee` - Shows that transactions from the same sender keep nonce order even when a later nonce would have a higher fee.
- `singleSender_singleTransaction` - Sanity check for the one-transaction case.
- `multiSender_highestFeeFirstWhenNoncesAreIndependent` - Verifies that when senders are independent, the builder chooses the highest fee first.
- `multiSender_nonceConstraintDelaysHighFeeTx` - Shows that a higher-fee transaction can be delayed if an earlier nonce from the same sender must go first.
- `nonceOrderIsStrictlyPreservedPerSender` - Confirms that nonce order is never violated for any sender.
- `nonceGaps_allTransactionsStillIncluded` - Ensures gaps in nonces do not drop transactions from the block.
- `orderingIsDeterministicRegardlessOfInputOrder` - Verifies that the same set of transactions always produces the same ordering.
- `shuffledInputProducesSameOutput` - Repeats the determinism check across multiple shuffled inputs.
- `sameFeeFromDifferentSenders_tiebrokenByTxHash` - Confirms that equal-fee transactions are ordered deterministically by transaction hash.
- `emptyTransactionList` - Verifies the empty-input edge case.
- `singleTransaction` - Verifies a single transaction survives ordering unchanged.
- `allTransactionsSameFeeAndSameSender` - Confirms same-sender transactions remain ordered by nonce even when fees match.
- `fourSendersInterleavedByFeeAndNonce` - Exercises a larger multi-sender scenario where fee ordering and nonce ordering interact across four accounts.
- `buildProducesNonceOrderedBlock` - Verifies the higher-level block builder preserves the same ordering guarantees.

### BlockBuildingAndPersistenceTest
- `blockBuilderOrdersByFeeDescending` - Verifies the builder places the highest-fee transaction first.
- `blockBuilderBreaksTiesByTxHash` - Verifies deterministic tie-breaking when fees are equal.
- `orderingIsDeterministicRegardlessOfInputOrder` - Verifies block ordering stays stable even if the input list is permuted.
- `blockHashIsDeterministic` - Verifies the same inputs always produce the same block hash.
- `differentTransactionsProduceDifferentHashes` - Verifies transaction content changes the block hash.
- `differentProposersProduceDifferentHashes` - Verifies the proposer address contributes to the block hash.
- `blockNumberIncrementsFromParent` - Verifies child blocks increment the block number.
- `blockPointsToParentHash` - Verifies a block points to its parent hash.
- `proposerIsStoredInBlock` - Verifies proposer metadata is preserved in the block.
- `finalizeAttachesReceipts` - Verifies finalization attaches receipts without changing the block hash.
- `serializeAndDeserializeBlock` - Verifies a finalized block survives JSON round-trip with transactions, receipts, and metadata intact.
- `serializeGenesisBlockWithNulls` - Verifies genesis-style blocks serialize and deserialize with null parent and proposer fields.
- `blockChainPersistsBlocksToDisk` - Verifies blocks are written to disk and can be read back.
- `blockChainWithoutPersistenceDoesNotCrash` - Verifies the blockchain still works when no persistence directory is configured.

### BlockValidationAndExecutionTest
- `executorDoesNotMutateCommittedStateWhenUsingSnapshot` - Verifies execution on a working copy does not mutate committed state.
- `blockChainRejectsBrokenParentLink` - Verifies the chain rejects a block with an invalid parent hash.
- `stateHashChangesWhenBalancesChange` - Verifies the world-state hash changes when balances change.
- `blockValidatorRejectsForgedTransactionSignature` - Verifies the block validator rejects a transaction signed by the wrong key.
- `blockValidatorRejectsTransactionMetadataMismatch` - Verifies the block validator rejects blocks whose transaction metadata does not line up with the payload.

## Serialization, Receipts, and Client Responses

### ClientResponseReceiptFieldsTest
- `clientResponsePreservesExtendedReceiptFields` - Verifies client responses preserve receipt hash, return data, contract address, gas used, and fee during serialization.
- `clientResponseAllowsEmptyOptionalReceiptFields` - Verifies optional receipt fields can be omitted and remain empty after parsing.

### ClientResponseCodecTest
- `nativeBalanceSnapshotRoundTrips` - Verifies native-balance query payloads encode and decode both the balance and the state hash snapshot.
- `committedResponseFormattingDecodesNativeAndTokenBalances` - Verifies committed responses are formatted correctly for native balance and ERC-20 balance queries.
- `canonicalResponseIdDependsOnReturnData` - Verifies response identity changes when `returnData` changes, which matters for Byzantine quorum comparison.
- `malformedNativeBalanceSnapshotIsRejected` - Verifies malformed native-balance snapshots are rejected by the codec.

### BlockSerializerReceiptRoundTripTest
- `serializerPreservesReturnDataAndContractAddress` - Verifies receipts survive block JSON round-trip without losing EVM return data or deployed contract address.

## Consensus, Replay, and Byzantine Resistance

### NativeTransferHotStuffTest
- `testMultipleSequentialTransfers` - Verifies four sequential native transfers commit through HotStuff and converge to the expected replica state.

### Erc20HotStuffTest
- `erc20BalanceQueryAndTransferCommitThroughConsensus` - Verifies an ERC-20 balance query and a token transfer both commit through the full replica stack.
- `erc20AllowanceLifecycleCommitThroughConsensus` - Verifies increaseAllowance, transferFrom, and decreaseAllowance all commit and converge across replicas.
- `erc20RevertingAllowanceDecreaseConvergesWithIdenticalStateHashes` - Verifies a reverting allowance decrease leaves replicas in the same state and with matching hashes.
- `erc20BalanceQueryLeavesStateUnchangedAcrossReplicas` - Verifies the token balance query path is read-only from the contract-state point of view.
- `unsafeApproveReplacementIsRejectedAndAllowanceStaysAtRemainingAmount` - Verifies the ERC-20 contract rejects unsafe nonzero-to-nonzero approval replacement.
- `erc20TransferProducesIdenticalReceiptsAndStateHashesAcrossReplicas` - Verifies the same ERC-20 transfer produces identical receipts and state hashes on all replicas.
- `approvalFrontrunningOrderDoesNotStackAllowance` - Verifies the frontrunning-resistant approval flow does not allow allowance stacking.

### ByzantineDivergentQcInjectionTest
- `honestReplicasRejectDivergentForgedQcInjection` - Verifies honest replicas ignore forged or divergent QC injections and do not advance state.

### ByzantineClientLeaderCollusionTest
- `honestReplicaRejectsTransactionFromUnknownAccount` - Verifies honest replicas reject a block containing a transaction from an unknown account not in world state.
- `honestReplicaRejectsLeaderTamperedTransactionContent` - Verifies honest replicas reject a block where the leader tampered with transaction content after signing.
- `honestReplicaRejectsBlockWithExtraMetadataEntries` - Verifies honest replicas reject a block whose transaction count does not match metadata count.

### EqualFeeOrderingConsensusTest
- `equalFeeTransactionsFromDifferentClientsProduceIdenticalBlockOrderAcrossReplicas` - Verifies equal-fee transactions from different clients produce identical block ordering on all replicas.
- `multipleEqualFeeTransactionsProduceConsistentStateAcrossReplicas` - Verifies multiple equal-fee transactions from both clients produce consistent state across replicas.

### ByzantineLeaderMalformedBlockStage2Test
- `honestReplicaRejectsForgedTransactionInPrepare` - Verifies a block carrying a forged transaction signature is rejected before it is cached or executed.
- `honestReplicaRejectsUnsignedTransactionInjectedByLeader` - Verifies an unsigned transaction injected by the leader is rejected.
- `honestReplicaRejectsDuplicateNonceTransactionsInSameBlock` - Verifies a malicious block with two transactions that reuse the same sender nonce is rejected deterministically.

### RequestReplayThroughConsensusTest
- `duplicateClientRequestReplayIsCommittedOnlyOnce` - Verifies replaying the same client request does not produce a second commit.
- `staleNonceDoubleSpendIsRejectedAfterFirstCommit` - Verifies a stale nonce cannot be reused to double-spend after a first commit.
- `sameSignedTransactionUnderDifferentRequestIdsDoesNotReplayTwice` - Verifies the same signed transaction cannot be replayed just by changing the outer request id.

### RepeatedDecideIdempotenceTest
- `repeatedDecideStyleExecutionAppliesACommittedBlockOnlyOnce` - Verifies the same DECIDE-style execution does not apply a committed block twice.

### ConsensusRaceProtectionTest
- `queryConsistencyDuringInFlightConsensusRound` - Verifies a read/query path stays consistent while consensus execution is stalled in the middle of a round.
- `timeoutDuringExecutionDoesNotDuplicateCommit` - Verifies a timeout and view change do not cause the same block to commit twice.

### InsufficientBalanceAfterTransferTest
- `secondTransferRejectedWhenBalanceDepleted` - Verifies a first large transfer can succeed while a second one fails once the sender’s remaining balance is no longer enough for value plus gas.

### QueryConsistencyTest
- `nativeBalanceConsistentAcrossReplicasAfterTransfer` - Verifies native balance is consistent across all replicas after a transfer.
- `tokenBalanceConsistentAcrossReplicasAfterTransfer` - Verifies ERC-20 token balance is consistent across all replicas after a transfer.
- `multipleTransfersProduceConsistentCumulativeBalances` - Verifies sequential transfers produce consistent cumulative balances across replicas.
- `bidirectionalTransfersProduceConsistentState` - Verifies bidirectional transfers between two clients produce consistent state across replicas.

### StressTest
- `testConcurrentTransfers` - Verifies concurrent native transfers complete successfully and preserve total balance conservation under load.

## Gas, Validation, and Execution Semantics

### TransactionExecutionTest
- `successfulNativeTransfer` - Verifies a basic native transfer succeeds and charges the correct fixed gas fee.
- `nonceIncrementsOnEachExecution` - Verifies the sender nonce increases after each successful execution.
- `refundsUnusedGasWhenGasLimitExceedsGasUsed` - Verifies unused gas is refunded when the gas limit exceeds the gas actually used.
- `outOfGasAbortsTransferButChargesFullGasLimit` - Verifies out-of-gas execution fails, charges the full limit, and refunds the transferred value.
- `insufficientBalanceForUpfrontCostFails` - Verifies transactions fail when the sender cannot afford value plus upfront gas.
- `transferToNonExistentAccountCreatesIt` - Verifies a native transfer can create a new receiver account.
- `zeroValueTransferStillChargesGas` - Verifies a zero-value native transfer still consumes gas.
- `nativeBalanceQueryReturnsSnapshotAndChargesGas` - Verifies the native balance query returns a state snapshot and is charged gas like an executable transaction.
- `higherGasPriceIncreaseFee` - Verifies a higher gas price increases the total fee and proposer credit.
- `proposerAccumulatesFees` - Verifies the proposer’s balance grows across multiple successful executions.
- `nullProposerBurnsFees` - Verifies fees are burned when no proposer is credited.

### TransactionExecutorErc20Test
- `erc20TransferUpdatesTokenBalancesAndChargesNativeFee` - Verifies an ERC-20 transfer updates token balances while still charging native gas.
- `erc20TransferRefundsUnusedGasWhenGasLimitExceedsActualUsage` - Verifies ERC-20 execution charges only for actual gas used and refunds the unused portion.
- `erc20TransferOutOfGasChargesFullLimitAndRevertsState` - Verifies out-of-gas ERC-20 execution charges the full gas limit and leaves token state unchanged.
- `increaseAllowanceThenTransferFromWorks` - Verifies the allowance flow works across transfer, increaseAllowance, and transferFrom.
- `contractCallRevertProducesFailedReceipt` - Verifies a reverted ERC-20 call still charges gas and returns a failed receipt.
- `decreaseAllowanceBelowZeroRevertsAndKeepsAllowanceUnchanged` - Verifies allowance underflow is rejected and the previous allowance remains intact.
- `approveNonZeroToNonZeroReplacementRevertsAndKeepsAllowanceUnchanged` - Verifies unsafe approval replacement is rejected and does not alter the allowance.

### TransactionValidationTest
- `acceptsValidTransaction` - Verifies a well-formed signed transaction passes validation.
- `rejectsWrongNonce` - Verifies validation rejects a transaction whose nonce is behind the committed nonce.
- `acceptsInsufficientBalanceAtValidationTime` - Verifies validation does not reject a transaction just because the sender balance is low for execution; that is handled later.
- `rejectsSignerSenderMismatch` - Verifies validation rejects signatures that do not match the sender address.
- `rejectsMissingTransactionSignature` - Verifies validation rejects unsigned transactions.
- `rejectsDuplicatePendingNonce` - Verifies validation rejects a nonce that is already pending for the same sender.
- `rejectsZeroGasPrice` - Verifies validation rejects a zero gas price.
- `rejectsZeroGasLimit` - Verifies validation rejects a zero gas limit.
- `rejectsNegativeValue` - Verifies validation rejects a negative transfer value.

### ConsensusIntegrationTest
- `drainBatchReturnsUpToMaxSize` - Verifies mempool draining respects the configured batch cap.
- `drainBatchReturnsAllWhenLessThanMax` - Verifies smaller batches drain completely.
- `drainBatchReturnsEmptyListWhenMempoolEmpty` - Verifies empty mempools produce no batch.
- `drainBatchPreservesFIFOOrder` - Verifies FIFO behavior is preserved when draining.
- `drainBatchRemovesDedupKeys` - Verifies drained requests are removed from deduplication tracking.
- `drainBatchDeduplicatesDuplicateReplayRequests` - Verifies replayed requests are not duplicated in the mempool.
- `transactionProtoRoundTrip` - Verifies transaction payloads survive proto serialization.
- `contractDeploymentProtoRoundTrip` - Verifies contract deployment payloads survive proto serialization.
- `fullPipelineSingleTransaction` - Verifies one transaction traverses the full consensus pipeline.
- `fullPipelineMultipleTransactionsDeterministicOrdering` - Verifies multiple transactions remain deterministically ordered through the full pipeline.
- `fullPipelineWithBlockFinalization` - Verifies full pipeline execution still finalizes receipts correctly.
- `blockPersistenceAfterExecution` - Verifies committed blocks are persisted after execution.
- `receiptMappingByTxHashAfterReorder` - Verifies receipts can still be matched to transactions after reordering.
- `emptyBlockBuildsSuccessfully` - Verifies an empty block builds without failure.
- `failedTransactionStillIncludedInBlock` - Verifies a failing transaction still appears in the committed block.
- `proposerReceivesGasFees` - Verifies proposer fee crediting works.
- `consecutiveBlocksBuildCorrectChain` - Verifies consecutive committed blocks link correctly.
- `multipleTransactionsGasFeesAllGoToProposer` - Verifies batch fees accumulate correctly for the proposer.
- `transactionsFailWhenBalanceExhaustedMidBlock` - Verifies later transactions fail once balance is exhausted mid-block.

### EdgeCaseRobustnessTest
- `nativeSelfTransferPreservesBalanceMinusGas` - Verifies a native self-transfer preserves balance minus gas fee.
- `nativeTransferToZeroAddressCreatesAccountAndCreditsValue` - Verifies a transfer to the zero address creates the account and credits the value.
- `erc20ApproveToZeroSucceedsWhenAllowanceIsNonZero` - Verifies approve(spender, 0) succeeds when allowance is nonzero.
- `erc20TransferFromOwnerToOwnerDeductsAllowanceButPreservesBalance` - Verifies transferFrom(owner, owner, amount) deducts allowance but leaves token balance unchanged.
- `contractCallToEoaFailsWithNoRuntimeCode` - Verifies a contract call targeting an EOA fails with no runtime code and charges full gas.
- `worldStateCopyIsIndependent` - Verifies copy() produces an independent snapshot.
- `stateHashIsStableWithoutModifications` - Verifies computeStateHash() is idempotent when no state changes occur.
- `stateHashChangesAfterBalanceModification` - Verifies computeStateHash() changes after a balance update.
- `proposerIsSenderFeesCreditedBack` - Verifies fee crediting is neutral when proposer equals sender.

## Miscellaneous Integration Tests

### Erc20AbiTest
- `smartContractMethodIdentifierProducesExpectedLength` - Verifies ABI selectors are 8 hex characters long.
- `encodeAddressIs32BytesLeftPadded` - Verifies ABI address encoding is 32-byte left padded.
- `encodeUint256Is32BytesLeftPadded` - Verifies ABI uint256 encoding is 32-byte left padded.
- `transferCalldataHasCorrectShape` - Verifies ERC-20 transfer calldata has the expected selector and arguments.
- `increaseAllowanceCalldataHasCorrectShape` - Verifies increaseAllowance calldata is formed correctly.
- `decreaseAllowanceCalldataHasCorrectShape` - Verifies decreaseAllowance calldata is formed correctly.
- `allowanceCalldataHasCorrectShape` - Verifies allowance calldata is formed correctly.
- `transferFromCalldataHasCorrectShape` - Verifies transferFrom calldata is formed correctly.
- `balanceOfCalldataHasCorrectShape` - Verifies balanceOf calldata is formed correctly.

## Approval Frontrunning Resistance (A)

### ApprovalFrontrunningResistanceTest
- `spenderCannotExceedOriginalAllowanceByRacingDecrease` - Verifies that a spender who races an owner's decreaseAllowance cannot extract more tokens than the original allowance.
- `ownerCanSafelyReduceRemainingAllowanceAfterPartialSpend` - Verifies an owner can zero out allowance after a partial spend, blocking further transferFrom.
- `approveNonZeroToNonZeroRevertsPreventsClassicFrontrunning` - Verifies the ERC-20 contract rejects direct nonzero-to-nonzero approve, forcing use of increaseAllowance/decreaseAllowance.

## Invalid Signature / Signer Mismatch (B)

### InvalidOuterSignatureStage2Test
- `forgedOuterSignatureIsDroppedByServers` - Verifies a client request signed with a random key is silently dropped by all replicas.
- `missingOuterSignatureIsDroppedByServers` - Verifies a client request with an empty signature field is rejected by all replicas.
- `tamperedRequestBodyIsDroppedByServers` - Verifies a validly signed request whose body is modified after signing is rejected.
- `spoofedClientIdIsDroppedByServers` - Verifies a request claiming a different client identity is rejected when the signature does not match.

## Replay / Nonce / Pipelining (C)

### FutureNonceAndPipeliningTest
- `futureNonceIsAcceptedWhenNotPending` - Verifies a future nonce is accepted at validation time when it is not already pending.
- `duplicateFutureNonceRejectedWhenAlreadyPending` - Verifies a duplicate future nonce is rejected if the same nonce is already pending.
- `staleNonceBelowCommittedIsRejected` - Verifies a nonce below the committed nonce is rejected.
- `multipleDistinctFutureNoncesAllAccepted` - Verifies multiple distinct future nonces from the same sender are all accepted.
- `pipelinedTransactionsExecuteInNonceOrder` - Integration test verifying three pipelined transfers commit in nonce order through full consensus.

## Double-Spend Prevention (D)

### DoubleSpendConsensusTest
- `sameNonceConflictingTransfersOnlyOneCommits` - Verifies two transactions with the same nonce to different receivers result in at most one commit.
- `sequentialTransfersExceedingBalanceOnlyFirstCommits` - Verifies a second transfer fails when the first drains the sender's balance.
- `rapidFireSameNonceOnlyOneCommits` - Verifies the same signed transaction submitted under three request IDs produces at most one committed transfer.

## Contract Execution Consistency (F)

### Erc20RevertReceiptConsistencyTest
- `revertingErc20TransferProducesIdenticalFailedReceiptsAcrossReplicas` - Verifies a reverting ERC-20 transfer produces identical failed receipts, state hashes, and block hashes on all replicas.
- `mixedSuccessAndRevertProduceIdenticalReceiptsAcrossReplicas` - Verifies a block with a successful transfer followed by a reverting one produces identical receipts on all replicas.
- `revertingDecreaseAllowanceProducesConsistentStateAndReceipts` - Verifies a reverting decreaseAllowance leaves allowance unchanged and produces consistent state across replicas.

## Gas Parameter Edge Cases (G)

### GasParameterEdgeCasesTest
- `absurdGasLimitRefundsExcessGas` - Verifies an absurdly large gas limit is accepted and excess gas is refunded.
- `exactGasLimitNoRefund` - Verifies a gas limit exactly matching the required gas results in no refund.
- `gasLimitOneLessThanRequiredCausesOutOfGas` - Verifies a gas limit one less than required causes an out-of-gas failure.
- `veryHighGasPriceChargesProportionally` - Verifies a high gas price charges proportionally higher fees.
- `upfrontCostExceedingBalanceFailsDeterministically` - Verifies upfront cost exceeding sender balance is rejected deterministically.
- `validationRejectsZeroGasPrice` - Verifies the validation layer rejects zero gas price.
- `validationRejectsZeroGasLimit` - Verifies the validation layer rejects zero gas limit.
- `gasLimitOfOneFailsWithOutOfGas` - Verifies a gas limit of 1 fails with out-of-gas and charges 1 unit of fee.
- `differentGasPricesProduceDifferentFeesButSameReceiverBalance` - Verifies different gas prices produce different fees but identical receiver balances.

---

