# DepChain - Dependable Blockchain - Group 5

A permissioned blockchain system implementing the **BasicHotStuff** BFT consensus protocol with BLS12-381 threshold signatures, built on top of authenticated UDP communication links.

---

## Overview

The system tolerates up to `f` Byzantine faults among `n = 3f+1` replicas. Clients submit append requests and wait for `f+1` identical responses. The consensus layer uses the four-phase BasicHotStuff protocol (PREPARE -> PRE-COMMIT -> COMMIT -> DECIDE) with threshold signatures for quorum certificates.

---

## Requirements

| Dependency | Version |
|---|---|
| Java (OpenJDK) | 17 |
| Maven | 3.8+ |
| tmux | latest available |
| openssl | required by `run.sh -k` |

## Setup on a New Machine or from the Submission Archive

### 1. Install System Dependencies

**Fedora / RHEL:**
```bash
sudo dnf install -y java-17-openjdk-devel maven tmux openssl
```

**Ubuntu / Debian:**
```bash
sudo apt install -y openjdk-17-jdk maven tmux openssl
```

Set `JAVA_HOME` if it is not already configured:

```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
```

### 2. Obtain the Project Archive

The project submission should be delivered as a self-contained zip archive. The archive should include the complete source tree plus the runtime assets needed to build and run the demo applications and tests, including:

- the Maven modules under `depchain/common`, `depchain/network`, `depchain/core`, `depchain/client`, and `depchain/tests`
- the Solidity contracts and generated ABI/bin artifacts under `depchain/solidity`
- the keystore material used by the servers, clients, and tests
- the native libraries under `depchain/core/native` and `depchain/tests/native`
- the configuration files under `depchain/config`
- this README and the Stage 2 test reference guide

If you are working from source control instead of the archive, clone the repository and enter the project root:

```bash
git clone https://github.com/tomasf18/dep-chain.git
cd dep-chain/depchain
```

### 3. Build the Project

The repository currently targets Java 17.

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

### Using `run.sh` (recommended)

`run.sh` launches the servers and clients in a `tmux` session and can also regenerate the EC key material used by the demo.

Run it from the `depchain/` directory:

```bash
./run.sh [options]
```

| Flag | Description | Default |
|---|---|---|
| `-f F` | Number of tolerated faults; starts `3F+1` servers | `1` |
| `-n N` | Number of clients to start | `2` |
| `-t` | Use `config-test.json` instead of `config-dev.json` | off |
| `-c` | Rebuild before starting | off |
| `-b` | Build only; do not launch `tmux` | off |
| `-k` | Regenerate EC keys without rebuilding | off |

**Examples:**

```bash
./run.sh                 # 4 servers, 2 clients, dev config
./run.sh -c              # rebuild then launch
./run.sh -k              # regenerate keys only
./run.sh -b              # compile and generate keys, no tmux
./run.sh -t              # launch with config-test.json
./run.sh -f 2 -n 3       # 7 servers and 3 clients
```

Servers are named `s0` through `s(3F)` and clients are named `client1` through `clientN`. The launcher waits briefly before starting clients so the replicas have time to initialize.

### Manual Launch

If you prefer to run processes directly, start them from the module directories.

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

## Submission Archive Contents

The archive should be self-contained enough for evaluation without needing the repository to be reconstructed from scratch. It should bundle:

- all source code for the Maven modules
- configuration files and generated contract artifacts
- the keystore material used by the demo and tests
- the native libraries required by the JVM runtime
- the Stage 1 and Stage 2 demo/test sources and their README guidance

## Demo Applications and Tests

The repository now organizes tests into Stage 1 and Stage 2 suites.

### Stage 1 Tests

These focus on the lower-level network and client-security primitives:

- `UDPFairLossTest`
- `StubbornLinkTest`
- `PerfectLinkTest`
- `AuthenticatedPerfectLinkTest`
- `InvalidClientSignatureTest`

### Stage 2 Integration Tests

These are the end-to-end demonstrations for dependability and Byzantine resistance:

- `NativeTransferHotStuffTest`
- `Erc20HotStuffTest`
- `ByzantineClientLeaderCollusionTest`
- `ByzantineDivergentQcInjectionTest`
- `ByzantineLeaderMalformedBlockTest`
- `InvalidOuterSignatureTest`
- `FutureNonceAndPipeliningTest`
- `DoubleSpendConsensusTest`
- `RequestReplayThroughConsensusTest`
- `RepeatedDecideIdempotenceTest`
- `ConsensusRaceProtectionTest`
- `QueryConsistencyTest`
- `Erc20RevertReceiptConsistencyTest`
- `ApprovalFrontrunningResistanceTest`
- `EqualFeeOrderingConsensusTest`
- `InsufficientBalanceAfterTransferTest`
- `StressTest`

### Stage 2 Unit Tests

These cover execution, validation, serialization, and block-building behavior in isolation:

- `BlockBuilderOrderingTest`
- `BlockBuildingAndPersistenceTest`
- `BlockValidationAndExecutionTest`
- `BlockSerializerReceiptRoundTripTest`
- `ClientResponseCodecTest`
- `ClientResponseReceiptFieldsTest`
- `ConsensusIntegrationTest`
- `EdgeCaseRobustnessTest`
- `Erc20AbiTest`
- `GasParameterEdgeCasesTest`
- `TransactionExecutionTest`
- `TransactionExecutorErc20Test`
- `TransactionValidationTest`

The full method-by-method catalog is documented in `depchain/tests/stage2_tests_SEE_THIS.md`.

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

Or run a single class with Maven:

```bash
mvn test -Dtest=NAME_OF_TEST_CLASS
```

---
## Protocol Summary

### BasicHotStuff Phases

```
Leader                          Replicas
  │                                │
  │── PREPARE(block, highQC) ────▶│  replicas check safeNode, vote PREPARE
  │◀─ PREPARE votes ──────────────│
  │── PRE-COMMIT(prepareQC) ────▶ │  replicas update prepareQC, vote PRE-COMMIT
  │◀─ PRE-COMMIT votes ───────────│
  │── COMMIT(preCommitQC) ──────▶ │  replicas lock on lockedQC, vote COMMIT
  │◀─ COMMIT votes ───────────────│
  │── DECIDE(commitQC) ─────────▶ │  all replicas execute, respond to client
```

A quorum certificate (QC) requires `n - f = 3` votes out of 4 replicas.

### View Change

If no progress within `INITIAL_TIMEOUT_MS` (10s), all replicas advance to the next view and send `NEW_VIEW` to the next leader. Timeouts use **exponential backoff** (doubling after 2 consecutive failures) to converge replica timers under asynchrony.

### Threshold Signatures (BLS12-381)

- Keys are generated offline via Shamir secret sharing over the BLS12-381 curve.
- Each replica holds a private share; all replicas share the same master public key.
- Voting: each replica independently produces a **partial signature** (no coordination).
- QC creation: leader combines any `f+1` partial signatures via Lagrange interpolation.
- QC verification: any replica verifies the combined signature against the master public key.

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