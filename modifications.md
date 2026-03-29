# HotStuff Patchws

Just for ref for the report: implementation changes made to stabilize the HotStuff flow 

## 1 - the fee-threshold batch selection was made deterministic and order-independent

### Files changed
- CommandMempool
- BasicHotStuffCoordinator
- CommandMempoolTest

### What changed
- Added fee-aware batch selection in the mempoool.
- The coordinator now selects the highest-fee sufficient batch from the full pending set instead of trusting fifo order
- Fee totals are now computed with BigInteger instead of th brittle "long" conversion from raw protobuf bytes
- Added a test for a high-fee transaction sitting behind lower-fee ones in the queue

### Problem solved
- The proposer was sometimes waiting even though the mempool already contained enough fees
- FIFO ordering could block a fee-sufficient transaction behind lower-fee transactions
- The previous fee calculation could misreport totals and even produce negative values

## 2 - Late DECIDE handling is now view-tolerant

### File changed
- BasicHotStuffCoordinator

### What changed
- DECIDE messages are no longer discarded just because the replica has already advanced to a later view
- The decide handler now validates the message using the decide message’s own view and leader
- If a valid decide arrives for an earlier committed view, the replica can still execute it
- A lagging replica now fast-forwards to the decided view’s successor before continuing

### Problem solved
- A slower replica could receive the client transactions, advance views due to timeouts, and then ignore the already-decided block
- This caused replicas to diverge in execution even though consensus had completed

### result
- Valid decided blocks are executed even if the replica has already moved on
- The node no longer gets stuck one view behind the rest of the quorum

## 3- out-of-order HotStuff messages are now buffered and recovered

### File changed
- BasicHotStuffCoordinator

### what changed
- valid PREPARE blocks are cached even if they arrive while a replica is behind or ahead of the current view
- DECIDE messages are buffered if the block is not yet known in the local tree
- once the block arrives, a pending DECIDE is executed immediately
- a replica can fast-forward to the decided view’s successor after executing a valid DECIDE

### problem solved
- a replica could fall behind if it timed out and advanced views before receiving the matching PREPARE/DECIDE pair
- the block would then never be executed locally even though consensus had completed

### result
- lagging replicas can catch up instead of getting stuck permanently behind
- out-of-order message delivery is handled more safely

## 4 - Null and stale consensus inputs are now better handled

### File changed
- BasicHotStuffCoordinator

### What changed
- Added null checks before using blocks fetched from the HotStuff tree
- PRE-COMMIT and COMMIT handlers now refuse to move QC state backwards when older QCs arrive late
- Duplicate votes from the same sender are ignored instead of silently overwriting existing votes

### Problem solved
- Byzantine or delayed messages could trigger null-pointer crashes
- Older QCs could overwrite newer local state
- Duplicate sender votes could hide equivocation and weaken traceability
- Single-thread wakeups could miss the right waiting path under contention

### result
- The replica is more resilient to late, duplicate, malformed and reordered consensus messages
- The protocol state is less likely to diverge under stress or Byzantine behavior

## 5 - ERC20 executor regression coverage was added for allowance underflow

### Files changed
- TransactionExecutorErc20Test

### What changed
- Added a regression test for `decreaseAllowance` when the requested reduction is larger than the current allowance.
- The test verifies both the revert and the fact that the allowance remains unchanged after the failed call.

### Problem solved
- The ERC20 allowance path previously lacked coverage for the underflow branch.
- The regression guards against accidental changes that could partially mutate allowance state on failure.

## 6 - ERC20 HotStuff integration coverage was added for transfer and allowance flows

### Files changed
- Erc20HotStuffTest

### What changed
- Added an end-to-end HotStuff test for `balanceOf` plus `transfer`.
- Added a second end-to-end HotStuff test for `increaseAllowance`, `transferFrom`, and `decreaseAllowance`.
- Reworked the integration test to use the real `client1` and `client2` identities instead of introducing a third holder account.
- Switched the assertions to read ERC20 balances and allowances directly from the replica world state.

### Problem solved
- The project now has live consensus coverage for the main ERC20 flows beyond the native transfer path.
- Direct world-state checks make the test more stable than depending on runtime seeding or tracer-based readback.

### result
- The ERC20 HotStuff test now exercises the real client flow and validates the contract state that replicas actually store.

## 7 - Genesis and runtime config were aligned to make client1 the initial token holder

### Files changed
- genesis.json
- config-dev.json
- config-test.json
- config-tamper.json

### What changed
- Updated the runtime blockchain configs so `initialTokenHolderAddress` points to `client1` everywhere.
- Updated the genesis deployment payload so the IST constructor mints the initial supply to `client1`.
- Kept the visible genesis holder account aligned with the same address and balance.

### Problem solved
- The tests no longer depend on a synthetic or third token-holder identity.
- Genesis, runtime configuration, and the live client roles now agree on the same initial owner.

### result
- The two-client model is now coherent from bootstrap through execution.

## 8 - World-state snapshotting was fixed so ERC20 storage survives consensus copies

### Files changed
- DepChainWorldState
- EvmService
- ServerContext

### What changed
- Added a tracked-account helper so the IST contract is preserved across `copy()` and `replaceWith()`.
- Added tracked storage-slot registration plus a refresh step for contract storage after EVM deployment and contract calls.
- Bootstrapped the ERC20 balance and allowance slots for the configured client accounts during server startup.

### Problem solved
- Contract execution was mutating live ERC20 storage, but the snapshot/copy path did not reliably preserve those contract accounts and slots.
- Read-only or follow-up consensus transactions could observe an empty or reset contract state after a world-state copy.

### result
- ERC20 balances and allowances now survive consensus state copies, which is what made the HotStuff integration tests pass consistently.

## Final implementation outcome
- Native transfer coverage remained intact.
- ERC20 unit and HotStuff coverage were added and stabilized.
- The deployment/config bootstrap was aligned around `client1` as the initial token holder.
- The world-state snapshot path now preserves the ERC20 contract state across replica execution.