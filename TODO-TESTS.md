### 1. Authorization and non-repudiation

Only the owner of an account can authorize spending from it, and every accepted operation is attributable to a real signer. 

Relevant attack scenarios:

* invalid outer client-request signature,
* invalid transaction signature,
* signer/address mismatch,
* forged request by Byzantine server,
* forged transaction wrapped in valid request envelope.

### 2. Replay resistance and uniqueness

A Byzantine client must not be able to execute the same intent multiple times by replaying requests, reusing signatures, or exploiting nonce handling. 

Relevant attack scenarios:

* duplicate request replay,
* duplicate signed transaction under different request IDs,
* duplicate nonce submission,
* future nonce abuse,
* repeated DECIDE delivery.

### 3. Financial safety / state-machine safety

The blockchain state must preserve:

* non-negative balances,
* no unauthorized transfer,
* no double spend,
* deterministic execution,
* same final state on all honest replicas. 

Relevant attack scenarios:

* insufficient balance,
* conflicting spends,
* malformed block from leader,
* divergent contract execution,
* invalid gas/value parameters.

### 4. Deterministic consensus and execution

Honest replicas must not diverge because of leader behavior, ordering ambiguity, or duplicate execution. This is where HotStuff and the state machine meet.

Relevant attack scenarios:

* Byzantine leader proposing malformed tx/block,
* equal-fee tie ambiguity,
* repeated DECIDE,
* proposal with invalid tx metadata,
* different replicas seeing same block but deriving different receipts/state.

### 5. ERC-20 stage-2 specific guarantee

The modified token contract must resist approval frontrunning, since the spec explicitly calls this out. 

Relevant attack scenario:

* malicious spender races the owner’s allowance reduction.

## Implementation details

Here is how I would refine our list into **must-have** and **nice-to-have** tests.

## Must-have tests

### A. Approval frontrunning resistance

This is mandatory because the spec explicitly names it.

**Guarantee:** spender cannot exploit allowance reduction to spend old + new allowance cumulatively. 

**Test scenario:**

1. Owner gives spender allowance 100.
2. Spender prepares `transferFrom(...,100)`.
3. Owner reduces allowance by 50-equivalent using our secure mechanism.
4. Race both operations in adversarial order.
5. Show that total tokens extractable never exceed the intended safe amount under our design.

**What this proves:** our ERC-20 deviation is justified and effective.

### B. Invalid signature / signer mismatch

This is the clearest authorization proof.

**Guarantee:** unauthorized clients cannot modify account state. 

**Tests:**

* invalid outer request signature,
* invalid tx signature,
* tx signed by one key but `from` field equals another address,
* Byzantine server cannot inject unsigned/forged tx into consensus.

### C. Replay / duplicate nonce / duplicate request

This is one of the most important stage-2 guarantees.

**Guarantee:** one logical operation cannot be applied multiple times.

**Tests:**

* exact same client request resent,
* same signed transaction resent in another request envelope,
* same nonce submitted twice,
* stale nonce replay after commit,
* repeated DECIDE message does not re-execute.

our “repeated DECIDE idempotence” belongs here and is definitely worth keeping.

### D. Double-spend attempts

This should be explicit.

**Guarantee:** a client cannot spend the same funds twice.

**Tests:**

* two transactions from same sender with same nonce and overlapping funds,
* two sequential txs whose combined value exceeds balance,
* conflicting txs injected close together so a Byzantine leader might try to include both.

This is stronger than just “insufficient balance” because it targets adversarial intent.

### E. Byzantine leader proposing malformed block/tx

This is probably the strongest consensus-security test after approval frontrunning.

**Guarantee:** honest replicas do not vote for invalid proposals.

**Tests:**

* leader proposes tx with bad signature,
* wrong metadata/transaction pairing,
* malformed block structure,
* tx from unknown signer/account,
* invalid nonce ordering inside block.

Expected result:

* honest replicas reject vote,
* no commit,
* view change eventually restores progress.

### F. Contract execution consistency across replicas

Very important for stage 2 because EVM integration is a major new component.

**Guarantee:** all honest replicas produce same receipts and same resulting state for same committed block.

**Tests:**

* same ERC-20 transfer/call executed on all replicas,
* compare balances, allowances, receipts, state hash, block state hash,
* include a reverting contract call too, not only success.

That proves deterministic execution, not just correctness.

## Strong additional tests

### G. Invalid gas parameter tests

Worth keeping, but present them as deterministic validation / resource-accounting guarantees, not generic input fuzzing.

**Guarantee:** malformed fee/gas parameters do not break safety or produce inconsistent execution.

**Tests:**

* zero gas price,
* zero gas limit,
* negative-equivalent impossible values at serialization boundary,
* too-low gas for native transfer,
* absurd gas limit if we want to reason about ordering abuse.

### H. Equal-fee ordering determinism

Good test. Keep it.

**Guarantee:** tie cases are deterministic across replicas.

**Tests:**

* equal fee, different tx hash,
* same senders/different senders,
* verify identical block ordering across all honest replicas.

This is not flashy, but it is exactly the sort of thing that prevents silent divergence.

### I. Insufficient balance tests

Keep them, but place them under “financial safety.”

**Guarantee:** balances never go negative. 

**Tests:**

* insufficient upfront cost,
* insufficient value after previous transfer,
* contract call fee exhaustion path,
* failure still deterministic.

### J. Byzantine client + Byzantine leader collusion

This is very good, especially because the spec explicitly mentions collusion. 

Example:

* client submits malformed but plausible tx,
* Byzantine leader includes it,
* honest replicas still refuse to vote.

This is really just a stronger version of malformed-block testing.

### K. Query consistency tests

Because our reads are quorum-based snapshots, not consensus-ordered operations, a test here could be useful.