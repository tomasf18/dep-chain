# DepChain

### `Grade: 19.0/20`

<div align="center">

**A permissioned blockchain implementing BasicHotStuff BFT consensus with native transfers, ERC-20 smart contracts and Byzantine fault tolerance**

</div>

---

## Table of Contents

- [Overview](#-overview)
- [Key Features](#-key-features)
- [Project Structure](#-project-structure)
- [Architecture](#-architecture)
- [Getting Started](#-getting-started)
- [Security Mechanisms](#-security-mechanisms)
- [Technologies](#-technologies)
- [Resources](#-resources)
- [Authors](#-authors)

---

## Overview

DepChain is a **fully-fledged permissioned blockchain system** built on the **BasicHotStuff Byzantine Fault Tolerant (BFT) consensus protocol**. It transforms raw UDP datagrams into authenticated, reliable, and ordered communication channels through a layered networking stack, then implements consensus and blockchain semantics on top.

The system progresses through two separable/complementary stages:

- **Stage 1**: Establishes a dependable communication substrate with authenticated perfect links and threshold-signed quorum certificates
- **Stage 2**: Adds account-based blockchain state, multi-transaction blocks, native token (DepCoin) operations, EVM-based smart contracts, and comprehensive gas accounting

DepChain enforces critical dependability guarantees including **authentication, authorization, non-repudiation, replay resistance, double-spend prevention, deterministic execution**, and resistance to Byzantine clients and servers - including collusion between them.

---

## Key Features

### Consensus & Protocol
- **BasicHotStuff BFT Protocol** (3f+1 fault tolerance model) with four-phase voting: PREPARE → PRE-COMMIT → COMMIT → DECIDE
- **BLS12-381 Threshold Signatures** for compact, publicly-verifiable quorum certificates without additional interactive rounds
- **View-change protocol** with exponential backoff to prevent perpetual desynchronization and ensure liveness under asynchrony
- **Leader-based proposal** with safeNode predicate to enforce HotStuff safety and prevent conflicting branch voting

### Communication Stack
- **Layered architecture** transforming unreliable UDP into authenticated, exactly-once, in-order delivery:
  - UDP Fair-Loss Link (with configurable fault injection for adversarial testing)
  - Stubborn Link (periodic retransmission)
  - Perfect Link (per-sender sequence numbers + filtering)
  - Authenticated Perfect Link (HMAC-SHA256 binding payload and sequence number)

### Blockchain & Transactions
- **Account-based state model** with externally owned accounts and contract accounts
- **Fee-driven, nonce-aware transaction ordering**:
  - Order by descending fee (highest fee first)
  - Strictly preserve per-sender nonce order (prevent sender nonce conflicts)
  - Deterministic tie-breaking by transaction hash
- **Deterministic block generation** ensuring all honest replicas build and interpret blocks identically
- **Persistent blockchain** with JSON-based block serialization and on-disk storage

### Smart Contracts & EVM Execution
- **Hyperledger Besu EVM** for deterministic smart-contract execution across all replicas
- **ISTCoin ERC-20 contract** with custom approval mechanism to resist frontrunning attacks:
  - Allowance changes via `increaseAllowance()` and `decreaseAllowance()` only
  - Direct `approve()` rejects unsafe nonzero-to-nonzero transitions
- **Gas accounting** with per-opcode costs, unused gas refunds, out-of-gas handling, and fee credits to proposers

### Security & Byzantine Resilience
- **Two-layer authentication**: Outer client-request signature + inner transaction signature
- **Replay protection** at request layer (client-id, request-id tracking) and blockchain layer (account nonces)
- **Block validation before voting** by replicas, preventing Byzantine leaders from injecting invalid transactions
- **Idempotent execution** protecting against repeated DECIDE message delivery
- **Atomic block execution** with snapshot state to prevent partial state application
- **Approval frontrunning resistance** through increaseAllowance/decreaseAllowance pattern

---

## Project Structure

```
depchain/
├── common/                    # Protocol messages (Protobuf), utilities, and shared types
├── network/                   # Communication stack: UDP → Stubborn → Perfect → Authenticated Perfect Link
├── core/                      # Blockchain state machine, HotStuff consensus, EVM execution
│   ├── hotstuff/              # BasicHotStuffCoordinator, threshold signatures, BLS manager
│   ├── blockchain/            # BlockChain, DepChainWorldState, TransactionExecutor, EvmService
│   └── byzantine/             # ByzantineCoordinator for adversarial testing
├── client/                    # ClientApp, ClientContext, ClientLibrary (sending requests & collecting quorum responses)
├── tests/                     # 21 comprehensive integration test classes covering all dependability guarantees
└── solidity/                  # ISTCoin.sol (ERC-20 with frontrunning resistance)
```

### Key Modules

| Module | Purpose |
|--------|---------|
| **common** | Protocol message definitions (Protobuf), configuration loading, cryptographic utilities, transaction serialization |
| **network** | Multi-layer communication: UDP fair-loss, stubborn retransmission, exactly-once delivery, HMAC authentication |
| **core** | Storage and execution engine: HotStuff state machine, transaction validation, gas accounting, Besu EVM integration |
| **client** | End-user interface: request submission, response collection, quorum finalization (f+1 matching responses) |
| **tests** | 21 integration test classes + 50+ individual test methods validating consensus, replay, Byzantine resistance, and contract execution |

---

## Architecture

### Layered Communication Stack

```
┌────────────────────────────┐
│ Blockchain Application     │
├────────────────────────────┤
│ HotStuff Consensus         │
├────────────────────────────┤
│ Authenticated Perfect Link │
├────────────────────────────┤
│ Perfect Link               │
├────────────────────────────┤
│ Stubborn Link              │
├────────────────────────────┤
│ UDP Fair-Loss Link         │
├────────────────────────────┤
│ Raw UDP Socket             │
└────────────────────────────┘
```

### HotStuff Consensus Flow

**Per View (Leader-driven):**

1. **PREPARE**: Leader proposes block after collecting 2f+1 NEW_VIEW messages
2. **PRE-COMMIT**: Leader collects 2f+1 PREPARE votes, broadcasts PRE-COMMIT QC
3. **COMMIT**: Leader collects 2f+1 PRE-COMMIT votes, broadcasts COMMIT QC
4. **DECIDE**: Leader collects 2f+1 COMMIT votes, broadcasts DECIDE message
5. **Execute**: Replicas execute block atomically on committed state snapshot, advance view

**Liveness**: Exponential backoff view-change timers + buffering future view messages ensure progress under asynchrony

### Blockchain State Machine

```
Block Proposal (fee-ordered, nonce-preserving)
    ↓
HotStuff Consensus (4-phase voting)
    ↓
Block Finalization & Execution (atomic snapshot)
    ↓
Receipt Generation (tx hash, status, gas used, return data)
    ↓
Deterministic State Hash (SHA256 of ordered account entries)
    ↓
Client Response (f+1 matching quorum confirms finalization)
```

---

## Getting Started

### Prerequisites

| Dependency | Version |
|---|---|
| Java (OpenJDK) | 17+ |
| Maven | 3.8+ |
| Protocol Buffer Compiler (protoc) | 3.20+ |

### Installation

**Fedora/RHEL:**
```bash
sudo dnf install -y java-17-openjdk-devel maven
```

**Ubuntu/Debian:**
```bash
sudo apt install -y openjdk-17-jdk maven
```

**Set JAVA_HOME (if not auto-configured):**
```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
```

### Project Setup & Build

**Clone and build:**
```bash
git clone https://github.com/tomasf18/DepChain.git
cd dep-chain/depchain
mvn clean install -DskipTests
```

### Running the System

**Terminal 1-4: Start four replicas (s0, s1, s2, s3)**
```bash
cd depchain/core
mvn exec:java -Dexec.mainClass=ist.depchain.core.ServerApp \
  -Dexec.args='../config/config-dev.json s0'
```
Repeat for s1, s2, s3 in separate terminals.

**Terminal 5: Start interactive client**
```bash
cd depchain/client
mvn exec:java -Dexec.mainClass=ist.depchain.client.ClientApp \
  -Dexec.args='../config/config-dev.json client1'
```

**Available client commands:**
- `transfer <amount> <receiver_address>` – Send native tokens
- `erc20-transfer <amount> <receiver_address>` – Transfer ERC-20 tokens
- `increase-allowance <spender> <amount>` – Approve spender incrementally
- `balance` – Query committed native balance
- `erc20-balance <token_address>` – Query ERC-20 balance
- `balances` – Show all replica balances
- `exit` – Gracefully shutdown

### Configuration

Three predefined configs in `depchain/config/`:

| File | Profile | Settings |
|------|---------|----------|
| `config-dev.json` | Development | No fault injection, instant delivery |
| `config-test.json` | Testing | 5% drop/duplicate/corruption, 100ms max delay |
| `config-tamper.json` | Adversarial | 50% corruption probability |

**Customization**: Edit JSON files to adjust N (replica count), f (fault tolerance), network delays, loss rates, etc.

### Running Integration Tests

**All tests:**
```bash
cd depchain/tests
mvn test
```

**Specific test class:**
```bash
mvn test -Dtest=Erc20HotStuffTest
```

**Key test suites:**
- `NativeTransferHotStuffTest` – Native token transfers through full consensus
- `Erc20HotStuffTest` – ERC-20 operations and contract execution
- `DoubleSpendConsensusTest` – Financial safety under Byzantine attacks
- `ApprovalFrontrunningResistanceTest` – ERC-20 allowance race prevention
- `ByzantineLeaderMalformedBlockTest` – Byzantine leader rejection
- `ConsensusRaceProtectionTest` – Execution idempotence under timeouts

---

## Security Mechanisms

### Dependability Guarantees

| Guarantee | Mechanism |
|-----------|-----------|
| **Consensus Safety** | HotStuff 3-phase locking + safeNode predicate + block re-validation |
| **Consensus Liveness** | View-change timeouts with exponential backoff + future view buffering |
| **Authentication** | HMAC per socket + public-key signatures on requests & transactions |
| **Authorization** | Transaction signatures must match sender address (account-level control) |
| **Non-Repudiation** | Inner transaction signature created by signer, verifiable across time |
| **Replay Protection** | Request layer: (clientId, requestId) deduplication; Blockchain layer: account nonces + idempotent execution |
| **Financial Safety** | Nonce ordering, double-spend detection, balance validation, proposer fee accumulation |
| **Deterministic Execution** | Fee-ordered with nonce preservation + deterministic tie-breaking + Besu EVM |
| **Replica Convergence** | Deterministic block ordering + state hash verification after each block |
| **Query Consistency** | Read operations ordered through consensus, returning last committed state snapshots |
| **ERC-20 Frontrunning Resistance** | Allowance via increaseAllowance/decreaseAllowance; blocks unsafe approve() transitions |

### Byzantine Resilience

DepChain withstands attacks from:
- **Byzantine clients**: Forged requests, unsigned transactions, spoofed identities
- **Byzantine leaders**: Conflicting proposals, invalid transactions, forged QCs
- **Byzantine replicas** (up to f < n/3): Equivocation, divergent votes, state divergence
- **Collusion**: Byzantine clients + leaders coordinating attacks

All attacks are detected and rejected before reaching consensus or state mutation.

---

## Technologies

| Category | Technology |
|----------|-----------|
| **Language** | Java 17 |
| **Build System** | Maven 3.8+ |
| **Messaging** | Protocol Buffers (gRPC-free, raw Protobuf serialization) |
| **Blockchain State** | Smart-contract execution via **Hyperledger Besu EVM** |
| **Smart Contracts** | Solidity (ERC-20 IST Coin) with frontrunning resistance |
| **Cryptography** | HMAC-SHA256 (link auth) + BLS12-381 threshold signatures (consensus QCs) + ECDH (key exchange) |
| **Consensus** | BasicHotStuff (Yin et al., 2019) |
| **Testing** | JUnit 5 (Jupiter) + custom integration test harness |
| **Persistence** | JSON-based block storage |

---

## Resources

### Project Report
- [**Full Technical Report**](./DependableChain_Report.pdf):
  - Design rationale for each layer
  - Threat model and countermeasures
  - Test coverage mapping to guarantees

### Key References
- [HotStuff: BFT Consensus in the Lens of Blockchain](https://arxiv.org/pdf/1803.05069)  -  Yin et al., 2019
- [BLS Signatures](https://github.com/herumi/bls)  -  Herumi Implementation (Ethereum 2.0 Phase 0)
- [ERC-20 Token Standard](https://eips.ethereum.org/EIPS/eip-20)  -  Token contract specification
- [OpenZeppelin – ERC20 Implementation](https://github.com/OpenZeppelin/openzeppelin-contracts/blob/master/contracts/token/ERC20)  -  Reference ERC-20 implementation

### Test Coverage
All 21 integration test classes document:
- Consensus safety and liveness
- Replay and double-spend prevention
- Byzantine resilience (forged transactions, divergent votes, collusion)
- Gas accounting and fee mechanics
- ERC-20 execution and frontrunning resistance
- State convergence across replicas

See `depchain/tests/src/test/java/ist/depchain/tests/integration/` for complete source.

---

## Authors

| <div align="center"><a href="https://github.com/tomasf18"><img src="https://avatars.githubusercontent.com/u/122024767?v=4" width="150px;" alt="Tomás Santos"/></a><br/><strong>Tomás Santos</strong></div> | <div align="center"><a href="https://github.com/pedropmad"><img src="https://avatars.githubusercontent.com/u/163666619?v=4" width="150px;" alt="Pedro Duarte"/></a><br/><strong>Pedro Duarte</strong></div> | <div align="center"><img src="https://avatars.githubusercontent.com/u/163666669?v=4" width="150px;" alt="André Pires"/></a><br/><strong>André Pires</strong></div> |
| --- | --- | --- |

---

<div align="center">

**Instituto Superior Técnico** • **Dependable Systems** • **2024/2025**

</div>
