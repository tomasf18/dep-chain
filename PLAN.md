# DepChain Stage 2 - Parallelization Guide

## Guiding principle

Build Stage 2 in this order:

> **state → transactions → native execution → blocks → consensus integration → EVM/contracts → testing**

This is better than starting with Solidity, because the contract only makes sense once the transaction/state/execution pipeline already exists.

---

## Dependency Graph

```text
Step 1 (State + Genesis) ───────┬──→ Step 2 (Transactions + Validation) ───┬──→ Step 3 (Native Execution + Gas Basics) ──┬──→ Step 5 (Consensus Integration) ──┬──→ Step 7 (Client Refactor)
                                 │                                            │                                              │                                     │
                                 │                                            └──→ Step 4 (Blocks + Persistence) ───────────┘                                     │
                                 │                                                                                                                                     │
                                 └──────────────────────────────────────────────────────────────────────────────→ Step 6 (Besu/EVM + ERC-20) ─────────────────────┘
                                                                                                                                                                      │
                                                                                                                                                                      └──→ Step 8 (Byzantine + Integration Tests)
```

---

## Final Step Breakdown

## Step 1 - Account Model, World State, Genesis

### Goal

Create the deterministic replicated state that all replicas will execute against.

### Tasks

* Define **EOA account**

  * address
  * native DepCoin balance
  * nonce
* Define **contract account**

  * address
  * native balance
  * code
  * storage
* Create `WorldState`

  * account lookup
  * account creation
  * balance updates
  * nonce updates
  * contract code/storage access
* Create **genesis loader**

  * initial accounts
  * initial native balances
  * optional initial nonces
  * placeholder for initial contract deployment metadata
* Add required dependencies

  * Besu EVM/datatype libs
  * crypto/address libs

### Output

A deterministic state layer with genesis initialization.

---

## Step 2 - Transaction Model, Signing, Validation

### Goal

Replace Stage 1 commands with signed blockchain transactions.

### Tasks

* Define transaction object:

  * `from`
  * `to`
  * `value`
  * `data/input`
  * `nonce`
  * `gasPrice`
  * `gasLimit`
  * `signature`
* Update protobuf messages
* Define canonical transaction hash / ID
* Implement client-side transaction signing
* Implement server-side validation:

  * signature valid
  * sender address matches signer
  * nonce matches current account nonce
  * `gasPrice > 0`
  * `gasLimit > 0`
  * sender can afford worst-case upfront cost

### Output

A valid transaction pipeline independent of consensus.

---

## Step 3 - Native Execution + Gas Basics

### Goal

Get blockchain execution working **before** EVM integration.

### Tasks

* Implement native DepCoin transfer execution
* Deduct sender balance
* Credit receiver balance
* Increment sender nonce
* Introduce **basic gas accounting**

  * first version can charge using deterministic rules
  * sender must afford `value + gasLimit * gasPrice`
* Define transaction result / receipt

  * success/fail
  * gas used
  * error reason
  * return data placeholder

### Output

A working state transition engine for native transactions.

---

## Step 4 - Block Structure, Ordering, Persistence

### Goal

Move from “one command” to “one ordered block of transactions.”

### Tasks

* Define block structure:

  * block hash / ID
  * parent hash
  * height
  * proposer
  * ordered list of transactions
  * block-level metadata
* Define deterministic ordering rule:

  * highest fee first
  * deterministic tie-breaker required

    * e.g. sender address + nonce, or tx hash
* Define block receipts / execution results
* Add JSON persistence for:

  * blocks
  * genesis
  * optionally receipts
* Keep world state persistence separate from block structure conceptually

### Output

A block model that can be proposed through HotStuff.

---

## Step 5 - Consensus Integration

### Goal

Refactor HotStuff from deciding single commands to deciding blocks.

### Tasks

* Replace command mempool with transaction mempool
* Leader builds block from valid mempool transactions
* Leader orders block transactions deterministically
* HotStuff proposal carries block payload
* On `DECIDE`:

  * execute full block deterministically
  * apply receipts
  * update world state
  * persist block
* Preserve Stage 1 safety fixes:

  * request/tx deduplication
  * idempotent decide handling
  * canonical client replies

### Output

End-to-end blockchain flow without contracts yet.

---

## Step 6 - Besu EVM Integration + ERC-20 Contract

### Goal

Add smart contracts on top of a working native blockchain core.

### Tasks

* Integrate Besu EVM execution environment
* Support **contract deployment**

  * `to = null`
  * input = deployment bytecode
* Support **contract calls**

  * ABI-encoded input
* Extract return data / revert status
* Store contract code + storage in world state
* Implement or deploy the ERC-20 contract:

  * `IST Coin`
  * symbol `IST`
  * decimals `2`
  * total supply `100000000`
* Replace `approve()` with:

  * `increaseAllowance()`
  * `decreaseAllowance()`
* Validate allowance-frontrunning mitigation

### Output

Smart contract support with the required ERC-20 token.

---

## Step 7 - Client Refactor

### Goal

Make the client usable for Stage 2 operations.

### Tasks

* Replace append/show-log style commands with:

  * native transfer
  * contract deployment
  * contract call
* Client signs every transaction
* Client tracks tx submission and receipts
* Add basic queries:

  * native balance
  * nonce
  * token balance
  * possibly contract read helpers
* Update client reply matching to Stage 2 receipt semantics

### Output

Usable transaction client for demos and testing.

---

## Step 8 - Byzantine, Security, and Integration Testing

### Goal

Prove the system satisfies the important Stage 2 guarantees.

### Tasks

* Invalid signature tests
* Replay / duplicate nonce tests
* Double-spend attempts
* Insufficient balance tests
* Invalid gas parameter tests
* Equal-fee ordering determinism tests
* Repeated DECIDE idempotence tests
* Byzantine leader proposing malformed tx/block
* ERC-20 allowance frontrunning scenario
* Contract deployment / call consistency across replicas

### Output

A test suite aligned with course expectations.

---

# Parallel Work Phases for a 3-Person Group

## Phase A - Foundations

### Stream 1 - State Layer

**Person A**

* Step 1: account types
* Step 1: world state
* Step 1: genesis loader

### Stream 2 - Transaction Layer

**Person B**

* Step 2: protobuf transaction messages
* Step 2: tx object
* Step 2: signing / verification
* Step 2: tx hash definition

### Stream 3 - Block Layer Skeleton

**Person C**

* Step 4: draft block model
* Step 4: block metadata
* Step 4: persistence format draft
* Step 4: deterministic ordering spec

### Phase A note

Person C can start early, but the block structure should remain flexible until Step 2 stabilizes the transaction format.

---

## Phase B - Core Execution

### Stream 1 - Native Execution

**Person A**

* Step 3: native transfer execution
* nonce updates
* balance updates
* account creation policy if needed

### Stream 2 - Validation + Mempool

**Person B**

* finish tx validation rules
* adapt mempool to transactions
* dedup by sender + nonce or tx hash
* prefilter invalid txs

### Stream 3 - Block Finalization

**Person C**

* finalize block structure
* implement fee ordering + tie-breakers
* block JSON persistence
* receipt persistence format

### Phase B note

At the end of this phase, you should already have:

* transactions
* validation
* world state
* native execution
* blocks

Even before HotStuff integration.

---

## Phase C - First End-to-End Blockchain

### Stream 1 - Consensus Integration

**Person A**

* Step 5: leader builds block from mempool
* Step 5: proposal carries blocks
* Step 5: execute block on decide

### Stream 2 - Receipt / Reply Path

**Person B**

* define tx receipts
* update server responses
* update client-side receipt matching
* map execution outcome to replies

### Stream 3 - Persistence / Recovery Support

**Person C**

* finalize block persistence
* state snapshot helper if needed
* chain inspection / debug utilities

### Phase C note

This is the first major merge point. After this phase, the system should already support:

* signed native transactions
* consensus over blocks
* deterministic execution
* committed receipts

No contracts yet.

---

## Phase D - Smart Contracts

### Stream 1 - Besu Integration

**Person A**

* Step 6: EVM deployment execution
* Step 6: contract call execution
* return/revert handling

### Stream 2 - ERC-20 Contract

**Person B**

* write Solidity contract
* compile bytecode
* ABI helpers for deployment/calls
* allowance-frontrunning mitigation demo

### Stream 3 - Client Contract Support

**Person C**

* Step 7: deploy-contract client flow
* Step 7: contract-call client flow
* query helpers for balances/token balances

### Phase D note

This phase is parallelizable because:

* Person A handles execution runtime
* Person B handles contract artifact and ABI layer
* Person C handles how clients interact with it

---

## Phase E - Testing and Hardening

### Stream 1 - Transaction / State Tests

**Person A**

* double spend
* nonce misuse
* insufficient balance
* duplicate execution

### Stream 2 - Contract / Security Tests

**Person B**

* approval frontrunning scenario
* malformed contract calls
* revert behavior
* gas misuse cases

### Stream 3 - Consensus / Integration Tests

**Person C**

* malicious leader block proposal
* deterministic ordering
* repeated DECIDE
* end-to-end multi-client scenarios

---

# Suggested Team Assignment

## Person A - State / Execution / Consensus

Best for the teammate most comfortable with core logic and distributed systems.

Owns:

* Step 1
* Step 3
* most of Step 5
* Besu runtime side of Step 6

## Person B - Transactions / Validation / Contract Logic

Best for the teammate strongest in crypto, protobuf, and validation-heavy code.

Owns:

* Step 2
* mempool validation side
* receipt definitions
* Solidity/ERC-20

## Person C - Blocks / Persistence / Client / Testing

Best for the teammate strongest in software organization and integration.

Owns:

* Step 4
* client refactor
* persistence
* test scaffolding and integration tooling

---

# Summary Table

| Phase | Stream A              | Stream B                    | Stream C                     |
| ----- | --------------------- | --------------------------- | ---------------------------- |
| A     | State + Genesis       | Transaction model + signing | Block model draft            |
| B     | Native execution      | Validation + tx mempool     | Block ordering + persistence |
| C     | Consensus integration | Receipts + reply path       | Persistence/debug tooling    |
| D     | Besu EVM runtime      | ERC-20 contract + ABI       | Client contract support      |
| E     | State/security tests  | Contract/security tests     | Consensus/integration tests  |

---

# Bottlenecks / Critical Path

The real critical path is:

> **Step 1 → Step 2 → Step 3 → Step 5 → Step 6 → Step 7 → Step 8**

Step 4 is parallel and should be ready before Step 5 merges.

So the main risk is not lack of parallelism - it is **integration timing** between:

* tx format
* execution semantics
* block structure
* consensus payload format

---

# Key Takeaway

The best way for your 3-person group to work is:

* **Person A** builds deterministic execution and consensus integration
* **Person B** builds the transaction/validation/contract side
* **Person C** builds block persistence, client integration, and testing