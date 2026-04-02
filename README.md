# DepChain - Dependable Blockchain - Group 5

A permissioned blockchain system implementing the BasicHotStuff BFT consensus protocol with BLS12-381 threshold signatures, built on top of authenticated UDP communication links.

---

## Table of Contents

- [Requirements](#requirements)
- [Setup on a New Machine or from the Submission Archive](#setup-on-a-new-machine-or-from-the-submission-archive)
- [Configuration](#configuration)
- [Running](#running)
- [Demo Applications and Tests](#demo-applications-and-tests)
- [Project Structure](#project-structure)
- [Authors](#authors)

---

## Requirements

| Dependency | Version |
|---|---|
| Java (OpenJDK) | 17 |
| Maven | 3.8+ |

## Setup on a New Machine or from the Submission Archive

### 1. Install System Dependencies

**Fedora / RHEL:**
```bash
sudo dnf install -y java-17-openjdk-devel maven
```

**Ubuntu / Debian:**
```bash
sudo apt install -y openjdk-17-jdk maven
```

Set `JAVA_HOME` if it is not already configured:

```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
```

### 2. Obtain the Project Archive

The project submission includes the complete source tree plus the runtime assets needed to build and run the demo applications and tests, including:

- the Maven modules under `depchain/common`, `depchain/network`, `depchain/core`, `depchain/client`, and `depchain/tests`
- the Solidity contracts and generated ABI/bin artifacts under `depchain/solidity`
- the keystore material used by the servers, clients, and tests
- the BLS native libraries under `depchain/core/native` and `depchain/tests/native`
- the configuration files under `depchain/config`

### 3. Build the Project

The repository currently targets Java 17. At the root of the project, run:

```bash
mvn clean install -DskipTests
```

If you only want to compile:

```bash
mvn clean compile
```

## Configuration

The project ships three runtime configurations under `depchain/config`:

| File | Purpose |
|---|---|
| `config-dev.json` | Default development profile with no fault injection |
| `config-test.json` | Fault-injection profile used for integration testing (`drop`, `duplicate`, and `tamper` set to 5%, `maxDelayMs` set to 100ms) |
| `config-tamper.json` | Tamper-focused profile with `tamperProbability` set to 50% |

### Current Config Shape

The active configuration now includes network, fault, crypto, and blockchain sections:

- `networkConfig`
  - `N = 4`
  - `f = 1`
  - `resendPeriodMillis = 1000`
  - `processes` entries include `host`, `port`, `role`, and `address`
- `faultConfig`
  - `dropProbability`
  - `duplicateProbability`
  - `tamperProbability`
  - `maxDelayMs`
- `cryptoConfig`
  - `signatureAlgorithm = SHA256withECDSA`
- `blockchainConfig`
  - `genesisPath`
  - `initialTokenHolderAddress`
  - `istContractAddress`
  - `istCreationBinPath`
  - `istAbiPath`

## Running

**Servers:**

```bash
cd core
mvn exec:java -Dexec.mainClass=ist.depchain.core.ServerApp -Dexec.args='../config/config-dev.json s0'
```

Repeat the same command for `s1`, `s2`, and `s3`.

**Clients:**

```bash
cd client
mvn exec:java -Dexec.mainClass=ist.depchain.client.ClientApp -Dexec.args='../config/config-dev.json client1'
```

### What the Demo Shows

The main application demonstrates the full permissioned blockchain stack:

- authenticated UDP communication links
- BasicHotStuff consensus with threshold signatures
- block proposal, voting, and finalization
- native token transfers and contract execution
- Byzantine-resilience handling under configured fault injection

## Demo Applications and Tests

This is a complete index of the files under `tests/src/test/java/ist/depchain/tests/stage2`.
It is meant as a quick orientation guide.

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
- `testMultipleSequentialTransfers` - Verifies three sequential native transfers commit through HotStuff and converge to the expected replica state.

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

## Approval Frontrunning Resistance

### ApprovalFrontrunningResistanceTest
- `spenderCannotExceedOriginalAllowanceByRacingDecrease` - Verifies that a spender who races an owner's decreaseAllowance cannot extract more tokens than the original allowance.
- `ownerCanSafelyReduceRemainingAllowanceAfterPartialSpend` - Verifies an owner can zero out allowance after a partial spend, blocking further transferFrom.
- `approveNonZeroToNonZeroRevertsPreventsClassicFrontrunning` - Verifies the ERC-20 contract rejects direct nonzero-to-nonzero approve, forcing use of increaseAllowance/decreaseAllowance.

## Invalid Signature / Signer Mismatch 

### InvalidOuterSignatureStage2Test
- `forgedOuterSignatureIsDroppedByServers` - Verifies a client request signed with a random key is silently dropped by all replicas.
- `missingOuterSignatureIsDroppedByServers` - Verifies a client request with an empty signature field is rejected by all replicas.
- `tamperedRequestBodyIsDroppedByServers` - Verifies a validly signed request whose body is modified after signing is rejected.
- `spoofedClientIdIsDroppedByServers` - Verifies a request claiming a different client identity is rejected when the signature does not match.

## Replay / Nonce / Pipelining 

### FutureNonceAndPipeliningTest
- `futureNonceIsAcceptedWhenNotPending` - Verifies a future nonce is accepted at validation time when it is not already pending.
- `duplicateFutureNonceRejectedWhenAlreadyPending` - Verifies a duplicate future nonce is rejected if the same nonce is already pending.
- `staleNonceBelowCommittedIsRejected` - Verifies a nonce below the committed nonce is rejected.
- `multipleDistinctFutureNoncesAllAccepted` - Verifies multiple distinct future nonces from the same sender are all accepted.
- `pipelinedTransactionsExecuteInNonceOrder` - Integration test verifying three pipelined transfers commit in nonce order through full consensus.

## Double-Spend Prevention 

### DoubleSpendConsensusTest
- `sameNonceConflictingTransfersOnlyOneCommits` - Verifies two transactions with the same nonce to different receivers result in at most one commit.
- `sequentialTransfersExceedingBalanceOnlyFirstCommits` - Verifies a second transfer fails when the first drains the sender's balance.
- `rapidFireSameNonceOnlyOneCommits` - Verifies the same signed transaction submitted under three request IDs produces at most one committed transfer.

## Contract Execution Consistency

### Erc20RevertReceiptConsistencyTest
- `revertingErc20TransferProducesIdenticalFailedReceiptsAcrossReplicas` - Verifies a reverting ERC-20 transfer produces identical failed receipts, state hashes, and block hashes on all replicas.
- `mixedSuccessAndRevertProduceIdenticalReceiptsAcrossReplicas` - Verifies a block with a successful transfer followed by a reverting one produces identical receipts on all replicas.
- `revertingDecreaseAllowanceProducesConsistentStateAndReceipts` - Verifies a reverting decreaseAllowance leaves allowance unchanged and produces consistent state across replicas.

## Gas Parameter Edge Cases

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

## Equal-Fee Ordering Determinism 

### EqualFeeOrderingConsensusTest
- `equalFeeTransactionsFromDifferentClientsProduceIdenticalBlockOrderAcrossReplicas` - Verifies equal-fee transactions from different clients produce identical block ordering on all replicas.
- `multipleEqualFeeTransactionsProduceConsistentStateAcrossReplicas` - Verifies multiple equal-fee transactions from both clients produce consistent state across replicas.

## Byzantine Client + Leader Collusion 

### ByzantineClientLeaderCollusionTest
- `honestReplicaRejectsTransactionFromUnknownAccount` - Verifies honest replicas reject a block containing a transaction from an unknown account not in world state.
- `honestReplicaRejectsLeaderTamperedTransactionContent` - Verifies honest replicas reject a block where the leader tampered with transaction content after signing.
- `honestReplicaRejectsBlockWithExtraMetadataEntries` - Verifies honest replicas reject a block whose transaction count does not match metadata count.

## Query Consistency 

### QueryConsistencyTest
- `nativeBalanceConsistentAcrossReplicasAfterTransfer` - Verifies native balance is consistent across all replicas after a transfer.
- `tokenBalanceConsistentAcrossReplicasAfterTransfer` - Verifies ERC-20 token balance is consistent across all replicas after a transfer.
- `multipleTransfersProduceConsistentCumulativeBalances` - Verifies sequential transfers produce consistent cumulative balances across replicas.
- `bidirectionalTransfersProduceConsistentState` - Verifies bidirectional transfers between two clients produce consistent state across replicas.

## Edge-Case Robustness 

### EdgeCaseRobustnessTest
- `nativeSelfTransferPreservesBalanceMinusGas` - Verifies a native self-transfer (from == to) preserves balance minus gas fee.
- `nativeTransferToZeroAddressCreatesAccountAndCreditsValue` - Verifies a transfer to 0x0 creates the zero-address account and credits the value.
- `erc20ApproveToZeroSucceedsWhenAllowanceIsNonZero` - Verifies approve(spender, 0) succeeds when allowance is nonzero (only non-zero → non-zero is blocked).
- `erc20TransferFromOwnerToOwnerDeductsAllowanceButPreservesBalance` - Verifies transferFrom(owner, owner, amount) deducts allowance but leaves token balance unchanged.
- `contractCallToEoaFailsWithNoRuntimeCode` - Verifies a contract call targeting an EOA fails with "no runtime code" and charges full gas.
- `worldStateCopyIsIndependent` - Verifies copy() produces a fully independent snapshot where mutations do not cross over.
- `stateHashIsStableWithoutModifications` - Verifies computeStateHash() is idempotent when called without intervening changes.
- `stateHashChangesAfterBalanceModification` - Verifies computeStateHash() changes after a balance modification.
- `proposerIsSenderFeesCreditedBack` - Verifies that when proposer == sender, fees credit back and net loss is only the transfer value.

---


### Running the Tests

Run the isolated Stage 2 suite from `depchain/tests`:

```bash
./run_stage2_isolated.sh
```

Run only one scope:

```bash
./run_stage2_isolated.sh --integration
./run_stage2_isolated.sh --unit
```

Run specific classes:

```bash
./run_stage2_isolated.sh ByzantineClientLeaderCollusionTest InvalidOuterSignatureTest ReplayAttackTest
```

Or run a single class with Maven. Under `depchain/tests`:

```bash
mvn test -Dtest=ist.depchain.tests.stage2.[unit|integration].[Class Name]
```

---

## Project Structure

```
depchain/
├── client/          - client application and client keystore
├── common/          - shared protobuf, config, and utility classes
├── core/            - server application, blockchain logic, and server keystore
├── network/         - UDP link stack and key-generation helpers
├── tests/           - Stage 1 and Stage 2 test suites, test runner, and test keystore
├── config/          - dev/test/tamper configuration files and address update script
├── solidity/        - ISTCoin contract source and generated ABI/bin artifacts
├── core/native/     - native BLS library used by the server runtime
└── tests/native/    - native BLS library used by the test runtime
```

---

## Authors

| <div align="center"><a href="https://github.com/andrepires2211"><img src="https://avatars.githubusercontent.com/u/163666619?v=4" width="150px;" alt="André Pires"/></a><br/><strong>André Pires</strong><br/>116452<br/></div> | <div align="center"><a href="https://github.com/pedropmad"><img src="https://avatars.githubusercontent.com/u/163666619?v=4" width="150px;" alt="Pedro Duarte"/></a><br/><strong>Pedro Duarte</strong><br/>116390<br/></div> | <div align="center"><a href="https://github.com/tomasf18"><img src="https://avatars.githubusercontent.com/u/122024767?v=4" width="150px;" alt="Tomás Santos"/></a><br/><strong>Tomás Santos</strong><br/>116122<br/></div> |
| --- | --- | --- |