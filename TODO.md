# TODO List

Important:
- Add logging options: nothing, info, debug

## Started, but not completed

- adapt stage 1 tests (adversary tests only) to stage 2 - **IMPORTANT: YOU SHOULD ALSO ADAPT THE PRUNING** 
- inlude "guaranteee that the f+1 received responses are actually identical (usar hash)" test
- READ OPERATIONS bothe Native and ERC20 balanceOf (client should be able to ask for their token balance and the replica should reply with it, provided the replica response includes the information needed to verify the balance is correct)
 Clients should see their balance by requesting nodes for it. But replicas sending an entire chain of blocks is very inefficient since it can be huge. We must consider a better alternative to ensure the client receives the state and can verify it is correct (merkle proofs, or maybe a snapshot of the state at a certain block number, etc) **EVERY REPLICA KEEPS A MERKLE ROOT OF THE WORLD STATE, WHEN CLIENT WANTS TO VERIFY ITS BALANCE, THE MERKLE ROOT CAN BE PROVIDED AND IF IT IS EQUAL FOR f+1 REPLICAS, THE CLIENT CAN TRUST THE BALANCE** -> this might not work because the client might want to verify a balance that is not in the latest block, but in a previous one. In that case, we can provide the client with the block hash and the state hash of that block, and the client can verify that the state hash matches the one in the block, and then verify that the balance is correct according to that state hash. This way, we can ensure that the client can verify their balance without having to receive the entire chain of blocks.

- add servers and smart contract (runtime code, bytecode de runtime) state to genesis -> IS THIS REALLY NEEDED?
- check TODO-TESTS.md for more details on the tests that are still missing.

### TODO-TESTS Status

Legend:
- `[x]` already covered by the current Stage 2 test suite
- `[ ]` still not clearly covered or not present yet

#### 1. Authorization and non-repudiation

Only the owner of an account can authorize spending from it, and every accepted operation is attributable to a real signer.

- [x] invalid outer client-request signature - covered by `InvalidOuterSignatureStage2Test`
- [x] invalid transaction signature - covered by transaction validation and forged-tx block rejection tests
- [x] signer/address mismatch - covered by transaction validation tests
- [x] forged request by Byzantine server - covered by `ByzantineClientLeaderCollusionTest`
- [x] forged transaction wrapped in valid request envelope - covered by Byzantine leader malformed block tests

#### 2. Replay resistance and uniqueness

A Byzantine client must not be able to execute the same intent multiple times by replaying requests, reusing signatures, or exploiting nonce handling.

- [x] duplicate request replay - covered by `RequestReplayThroughConsensusTest`
- [x] duplicate signed transaction under different request IDs - covered by `RequestReplayThroughConsensusTest`
- [x] duplicate nonce submission - covered by pending-nonce validation, stale-nonce replay, and same-block duplicate nonce rejection
- [ ] future nonce abuse - partially covered by `FutureNonceAndPipeliningTest`, but no explicit flooding/DoS case is represented yet
- [x] repeated DECIDE delivery - covered by `RepeatedDecideIdempotenceTest`

#### 3. Financial safety / state-machine safety

The blockchain state must preserve non-negative balances, no unauthorized transfer, no double spend, deterministic execution, and the same final state on all honest replicas.

- [x] insufficient balance - covered by native transfer and consensus integration tests
- [x] conflicting spends - covered by replay / stale nonce / same-block duplicate nonce tests
- [x] malformed block from leader - covered by Byzantine malformed block tests
- [x] divergent contract execution - covered by ERC-20 consensus tests and state-hash checks
- [x] invalid gas/value parameters - covered by transaction validation and executor tests
- [x] non-negative balances - enforced by balance checks and insufficient-balance scenarios
- [x] no unauthorized transfer - covered by signature and sender validation
- [x] no double spend - covered by replay and nonce tests
- [x] deterministic execution - covered by block building, consensus integration, and repeated execution tests
- [x] same final state on all honest replicas - covered by HotStuff integration and ERC-20 consensus tests

#### 4. Deterministic consensus and execution

Honest replicas must not diverge because of leader behavior, ordering ambiguity, or duplicate execution.

- [x] Byzantine leader proposing malformed tx/block - covered by Byzantine leader tests
- [x] equal-fee tie ambiguity - covered by block ordering tests
- [x] repeated DECIDE - covered by `RepeatedDecideIdempotenceTest`
- [x] proposal with invalid tx metadata - covered by block validation tests
- [x] different replicas seeing same block but deriving different receipts/state - covered by ERC-20 consensus tests and receipt/state-hash comparisons

#### 5. ERC-20 stage-2 specific guarantee

The modified token contract must resist approval frontrunning.

- [x] malicious spender races the owner’s allowance reduction - covered by `Erc20HotStuffTest` approval-frontrunning coverage

#### Implementation detail notes

The following items are useful for discussion or future hardening, but they are not yet represented as explicit Stage 2 test cases:

- [x] invalid outer client-request signature
- [x] forged request by Byzantine server
- [ ] future nonce abuse - partially covered by `FutureNonceAndPipeliningTest`, but no explicit flooding/DoS case is represented yet

#### Useful coverage already in the suite

- `TransactionExecutionTest` covers native transfer fee charging, gas refunds, out-of-gas behavior, and balance safety.
- `TransactionExecutorErc20Test` covers actual EVM gas charging, refunding, out-of-gas revert behavior, and ERC-20 allowance safety.
- `ConsensusRaceProtectionTest` covers execution/view-change race conditions.
- `ByzantineDivergentQcInjectionTest` covers forged QC injection.
- `BlockBuilderOrderingTest` covers fee ordering, nonce ordering, tie-breaking, and determinism.

---

## To Verify


## Doubts:
- For system liveness, is it enough to always have newViews after timeout, or is it necessary to always be proposing new blocks even without transactions?
- Why is it the client who defines the gas price? Shouldn't it be the EVM or a global variable of the system? 
ANS.: In an EVM-based blockchain, gas pricing is actually a hybrid system where the protocol sets a baseline, but the client chooses the final "bid" to ensure their transaction is processed. While it might seem like a global variable should suffice, the system uses a market-driven approach to manage network congestion and security. The blockchain does include a global variable called the Base Fee, which is automatically calculated by the protocol based on how busy the network was in the previous block. This ensures a predictable minimum cost for every user. However, the client (the user's wallet) defines an additional Priority Fee (or "tip"). This tip is necessary because the EVM has limited space per block; by allowing users to set their own price, the system creates an incentive auction. High-priority transactions can "outbid" others to be included immediately by validators, while low-priority users can choose to pay less and wait for a quieter time.
Ultimately, the client defines the price because only the user knows the economic value of their specific transaction. If the price were a strictly fixed global variable, the network would have no way to prioritize critical actions during high traffic, making it vulnerable to spam and preventing a functional market for network space.

## Completed

- Approval Frontrunning attacks are handled (create tests)
(ERC20 = IST Coin -> see "Native currency vs Tokens" section below)
- ERC20 token transfers (client should be able to ask to transfer tokens to another account, and the replica should execute it if the client has enough balance)
- ERC20 increaseAllowance (client should be able to ask to increase the allowance they have given to another account, and the replica should execute it)
- ERC20 decreaseAllowance (client should be able to ask to decrease the allowance they have given to another account, and the replica should execute it if the client has enough allowance)
- ERC20 transferFrom (client should be able to ask to transfer tokens on behalf of another account, and the replica should execute it if the client has enough allowance)
For the previous points, pay attention to:
- The returnData should be used when required to send response data to the client. For example, in the case of balanceOf, it should carry the client's balance in the reply.
- Separate 2 Client API calls for getting balance: one that returns the balance in the native currency (DepCoin) and another that returns the balance of a specific token (ERC-20). This is because they are stored differently in the world state and require different logic to retrieve.

- Order transactions by fee, **but for the same client order by nonce - "the transactions of each client are ordered by its nonce/submission order"**
- native transfers working with EVM execution
- tests for native transfers and state convergenge among replicas
- Clients need to sign requests and replicas need to verify
- Application-level sequence numbers to prevent replay attacks (application tracks client state: "Paulo has 10 transactions")
- Line in verifyQC
- 2f+1 fix code and report
- Guarantee that the f+1 received responses are actually identical (use hash)
- Management of tree pruning for conflicting values (when locked, prune the non-locked ones)
- Create tests where this happens (conflicting values)
