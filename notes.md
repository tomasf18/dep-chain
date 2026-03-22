- Gestao de pruing da arvore para valores conflituosos (quando locked, dar prune aos nao locked)
criar testes em que isto acontece (valores conflituosos)




feito:

- linha no verifyQC -> DONE
- 2f+1 - fix code e report -> DONE
- guaranteee that the f+1 received responses are actually identical (usar hash) -> DONE
- precissamos de sequence numbers no nivel da aplicacao para evitar replay attacks (a aplicacao guarda o estado dos clients: "O paulo tem 10 transacoes") -> DONE
- clients precisam de assinar pedidos e replicas de verificar -> DONE

- Before the server rejected any client request whose requestId was not strictly greater than the highest seen one. That was too brittle over UDP and incompatible with the richer transaction flow we will need in Stage 2. A valid delayed request can be discarded just because a later one arrived first.
Por isso alterei modo como client requests sao tracked no server -> no message handler, já nao se ignoram requests com lower sequence numbers -> agora o message handler tem um callback para o coordinator, que é chamado quando um request é committed, e o message handler marca esse request como executed (e responde ao cliente) -> isto é necessario para evitar replay attacks, e para garantir que o client recebe resposta mesmo que o request seja re-proposto varias vezes (por exemplo, se o leader falha depois de propor mas antes de commit)


Plan:

Great — this is the **most important moment in your project**, and you’re doing the right thing by asking for a structured plan before coding.

I’m going to give you a **full roadmap from zero → working Stage 2**, specifically tailored to your current codebase (HotStuff + mempool + request tracking).

---

# 🧠 0. Problem Understanding (What Stage 2 *really* is)

Right now your system does:

> consensus over **commands** → “append string”

Stage 2 becomes:

> consensus over **transactions** → deterministic execution → replicated **state machine (blockchain)**

You are basically turning your system into a **mini Ethereum-like blockchain**.

---

# 🧱 1. Concepts You MUST Understand First

Don’t skip this — but also don’t overstudy.

## 1.1 Accounts & State

* Each user has:

  * address (public key)
  * balance
  * nonce (sequence number)

👉 Think:

```java
class Account {
    long balance;
    long nonce;
}
```

---

## 1.2 Transactions

A transaction is:

* signed by sender
* modifies state

Example:

```text
Alice → Bob, amount=10
```

---

## 1.3 Nonce (VERY IMPORTANT)

* Prevents replay attacks
* Ensures ordering per account

Rule:

```text
tx.nonce == account.nonce
```

---

## 1.4 Gas (simple version for your project)

You don’t need full Ethereum gas.

Think:

> gas = cost to execute a transaction

For now:

* fixed cost per transaction (e.g., 1)
* deducted from sender balance

---

## 1.5 Blocks

Instead of one command per proposal:

👉 now leader proposes a **block of transactions**

```java
class Block {
    List<Transaction> txs;
    String parentHash;
}
```

---

## 1.6 Determinism (CRITICAL)

All replicas must:

* execute same transactions
* in same order
* get same result

Otherwise → consensus breaks

---

# 🏗️ 2. High-Level Architecture Changes

You already have:

```
Client → Request → HotStuff → Execute(Command)
```

You will evolve to:

```
Client → Transaction → Mempool → HotStuff → Block → Execute → State
```

---

# 🔁 3. Migration Plan (Step-by-Step)

## ✅ STEP 1 — Replace Command with Transaction

### Goal

Stop using:

```java
Command("append", data)
```

Create:

```java
class Transaction {
    String from;
    String to;
    long amount;
    long nonce;
    byte[] signature;
}
```

### Changes

* protobuf → add Transaction message
* ClientLibrary → build + sign transactions
* MessageHandler → validate transaction signature

---

## ✅ STEP 2 — Replace Blockchain (String list → State)

### Current:

```java
List<String> log;
```

### Replace with:

```java
class WorldState {
    Map<String, Account> accounts;
}
```

Add:

* `getAccount(address)`
* `applyTransaction(tx)`

---

## ✅ STEP 3 — Implement Transaction Validation

Before adding to mempool:

Check:

```text
1. signature valid
2. account exists
3. nonce correct
4. balance >= amount + gas
```

If not → reject

---

## ✅ STEP 4 — Upgrade Mempool

Current mempool = FIFO queue

Change to:

* store Transactions instead of Commands
* deduplicate by `(sender, nonce)`

Optional (later):

* sort by fee (not required now)

---

## ✅ STEP 5 — Propose BLOCKS (not single tx)

### Change HotStuff proposal

Instead of:

```java
Command cmd = mempool.dequeue();
```

Do:

```java
List<Transaction> txs = mempool.getBatch(N);
Block block = new Block(txs, parent);
```

---

## ✅ STEP 6 — Execution Engine

Replace `CommandExecutor` with:

```java
class ExecutionEngine {
    void applyBlock(Block block) {
        for (tx : block.txs) {
            applyTransaction(tx);
        }
    }
}
```

---

## ✅ STEP 7 — Transaction Application Logic

Core logic:

```java
void applyTransaction(tx):
    sender = state.get(tx.from)

    if sender.balance < tx.amount + gas → reject

    sender.balance -= (amount + gas)
    receiver.balance += amount
    sender.nonce++
```

---

## ✅ STEP 8 — Deterministic Failure Handling

Important:

* Invalid tx inside block must NOT break consensus
* Instead:

  * skip OR mark failed
  * but all replicas must do same

👉 easiest:

```text
only include VALID txs in proposal
```

---

## ✅ STEP 9 — Client Response Redesign

Instead of:

```text
committed block id
```

Return:

```text
txHash
status (SUCCESS / FAIL)
```

---

## ✅ STEP 10 — Genesis Block

Initialize:

```java
accounts:
  client1 → 1000
  client2 → 1000
```

---

# ⚠️ 4. Critical Pitfalls (VERY IMPORTANT)

## ❌ 1. Non-deterministic execution

* using system time
* random numbers
* unordered maps

👉 MUST avoid

---

## ❌ 2. Applying invalid transactions differently across replicas

👉 must be:

* either filtered before proposal
  OR
* deterministically rejected

---

## ❌ 3. Double execution (you already fixed this 👍)

---

## ❌ 4. Missing nonce checks

👉 leads to:

* replay attacks
* inconsistent state

---

# 🔐 5. Security Mapping (What you already fixed helps here)

| Feature           | Stage 2 Role              |
| ----------------- | ------------------------- |
| request tracking  | → becomes nonce logic     |
| mempool dedup     | → prevents tx duplication |
| idempotent commit | → prevents double-spend   |
| client quorum     | → ensures correct result  |

---

# 🧪 6. Testing Strategy (MANDATORY)

## Basic tests

* transfer A → B
* multiple transactions in block

## Edge cases

* insufficient balance
* wrong nonce
* duplicate tx

## Byzantine tests

* invalid signature
* malicious leader proposing bad tx
* out-of-order tx

---

# 📦 7. Suggested Implementation Order (IMPORTANT)

Do NOT jump randomly. Follow this:

### Phase 1 (foundation)

1. Transaction class + protobuf
2. Client sends transactions
3. Server validates signature

### Phase 2 (state)

4. WorldState + Account
5. applyTransaction()

### Phase 3 (integration)

6. Replace CommandExecutor
7. Update mempool

### Phase 4 (consensus integration)

8. Block instead of single command
9. execute block on DECIDE

### Phase 5 (polish)

10. client response update
11. testing

---

# 🎯 Final Advice

You are NOT building Ethereum.

You are building:

> “HotStuff + deterministic transaction execution”

Keep it:

* simple
* deterministic
* consistent

---

# 🚀 If you want next

We can go step-by-step together.

I suggest next:

👉 “Design the Transaction + protobuf + signature format”

This is the **foundation**, and if you get it wrong, everything else becomes painful.
