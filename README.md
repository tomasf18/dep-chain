# DepChain - Dependable Blockchain - Group 5

A permissioned blockchain system implementing the **BasicHotStuff** BFT consensus protocol with BLS12-381 threshold signatures, built on top of authenticated UDP communication links.

---

## Overview

The system tolerates up to `f` Byzantine faults among `n = 3f+1` replicas. Clients submit append requests and wait for `f+1` identical responses. The consensus layer uses the four-phase BasicHotStuff protocol (PREPARE -> PRE-COMMIT -> COMMIT -> DECIDE) with threshold signatures for quorum certificates.

---

## Requirements

| Dependency | Version |
|---|---|
| Java (OpenJDK) | 21 |
| Maven | 3.8+ |


## Setup on a New Machine

### 1. Install System Dependencies (install only those you don't have)

**Fedora / RHEL:**
```bash
sudo dnf install -y java-21-openjdk-devel maven
```

**Ubuntu / Debian:**
```bash
sudo apt install -y openjdk-21-jdk maven
```

Set `JAVA_HOME` if not already set (Bash shell):
```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
```

### 2. Clone the Repository

```bash
git clone https://github.com/tomasf18/dep-chain/tree/main
cd depchain
```

### 3. Build the Project

```bash
cd ../depchain
mvn clean compile
```

## Configuration

Two config files are provided:

| File | Purpose |
|---|---|
| `config-dev.json` | Zero fault injection, fast timeouts - used for development |
| `config-test.json` | Realistic fault injection (10% drop/duplicate/tamper) - used for testing |

### Config Structure

```json
{
  "networkConfig": {
    "N": 4,
    "f": 1,
    "resendPeriodMillis": 500,
    "processes": {
      "client1": { "host": "localhost", "port": 4001 },
      "client2": { "host": "localhost", "port": 4002 },
      "s0":      { "host": "localhost", "port": 5000 },
      "s1":      { "host": "localhost", "port": 5001 },
      "s2":      { "host": "localhost", "port": 5002 },
      "s3":      { "host": "localhost", "port": 5003 }
    }
  },
  "faultConfig": {
    "dropProbability": 0.0,
    "duplicateProbability": 0.0,
    "tamperProbability": 0.0,
    "maxDelayMs": 0
  },
  "cryptoConfig": {
    "signatureAlgorithm": "SHA256withECDSA"
  }
}
```

---

## Running

### Using `start.sh` (recommended)

`start.sh` launches all servers and clients in a single `tmux` session. Run it from the `depchain/` directory:

```bash
./start.sh [options]
```

| Flag | Description | Default |
|---|---|---|
| `-f F` | Number of tolerated faults; starts `3F+1` servers | `1` |
| `-n N` | Number of clients to start | `1` |
| `-t` | Use `config-test.json` (fault injection enabled) | uses `config-dev.json` |
| `-c` | Recompile before starting | off |

**Examples:**

```bash
./start.sh                  # 4 servers, 1 client, dev config
./start.sh -c               # same but recompile first
./start.sh -f 2 -n 3        # 7 servers, 3 clients, dev config
./start.sh -t               # 4 servers, 1 client, test config (fault injection)
./start.sh -f 2 -n 2 -t -c  # 7 servers, 2 clients, test config, recompile
```

**tmux navigation:**

| Keys | Action |
|---|---|
| `Ctrl+b 0` | Switch to servers window |
| `Ctrl+b 1` | Switch to clients window |
| `Ctrl+b arrow` | Move between panes |

Servers are named `s0`…`s(3F)` and clients `client1`…`clientN`. Clients wait 2 seconds after launch to give servers time to initialize.

---

### Manual (without tmux)

All server commands run from `depchain/core/`, client commands from `depchain/client/`.

**Servers:**

```bash
mvn exec:java -Dexec.args='../config-dev.json s0'
mvn exec:java -Dexec.args='../config-dev.json s1'
mvn exec:java -Dexec.args='../config-dev.json s2'
mvn exec:java -Dexec.args='../config-dev.json s3'
```

**Clients:**

```bash
mvn exec:java -Dexec.args='../config-dev.json client1'
```

### Expected Server Startup

```
[BLS | INFO] - BLS12-381 initialized
[BLS | INFO] - Loaded BLS keys for replica index 1
[SERVER_APP | INFO] Successfully started
[AUTHENTICATOR | INFO] - All handshakes complete.
```

### Client Menu

```
=== [client1] Select an action ===
  1: Append to log
  2: View log
  exit
```
---
## JUnit Tests
We have implemented a test suite to validate both the network abstractions as well as the Basic HotStuff protocol's resilience against Byzantine faults.

**[IMPORTANT]**

**Execution Recommendation**: Due to the extensive use of network resources (e.g, UDP ports) in our test suites, it is recommended to run each of the implemented tests individually. Running the full suite of tests sequentially (e.g, via mvn test), may result in intermittent failures because the resources from the previous test were not released.

### How to run

All commands should be executed inside the directory: **dep-chain/depchain/tests**.

To run a specific test class, use the following Maven command:

```bash
mvn test -Dtest=NAME_OF_TEST_CLASS
```

### Test case descriptions
**UDPFairLossTest** - Validates basic sending/receiving over UDP Fair-Loss layer.

**StubbornLinkTest** - Validates message retransmission logic when the packets are lost.

**PerfectLinkTest** - Ensures that the message is delivered "exactly once".

**AuthenticatedPerfectLinkTest** - Tests normal utilization of the layer without any adversaries. Also tests if the layer is capable of detecting man-in-the-middle (impersonation and data tampering).

**HappyPathTest** - Standard execution of the system with 4 honest replicas. Validates protocol completion.

**ResilienceTest** - Demonstrates f = 1 tolerance with one replica offline (Simulation of Crash fault).

**ByzantineSilentLeaderTest** - Validates that the system rotates the leader via timeouts when the leader is silent.

**ByzantineLeaderEquivocate** - Validates that if a Leader tries to send different proposals to different replicas, it does not cause a fork (replicas with different commits).

**ByzantineCorruptReplicaTest** - Validates that the system discards malicious information introduces by a malicious replicas.

**MultipleRequestsTest** - Tests stability and sequencing under a continuous request stream.

**MultipleClientsTest** - Validates concurrent interactions from multiple independent clients.

### Test source location
The source code for each test is inside the directory: **dep-chain/depchain/tests/src/test/java/ist/depchain/tests**

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
├── client/          - client application
├── common/          - shared protobuf definitions, Config, ProcessInfo
├── core/            - server application
│   ├── keystore/    - BLS key shares (generated, not committed)
│   │   ├── s0/
│   │   ├── s1/
│   │   ├── s2/
│   │   └── s3/
│   └── src/main/java/ist/depchain/core/
│       ├── hotstuff/
│       │   ├── BasicHotStuffCoordinator.java
│       │   ├── BasicHotStuffUtils.java
│       │   ├── BasicHotStuffTree.java
│       │   ├── CommandMempool.java
│       │   └── tsignatures/
│       │       ├── BLSManager.java
│       │       ├── BLSThresholdSig.java
│       │       └── BLSKeyGenApp.java
│       ├── BlockChain.java
│       ├── MessageHandler.java
│       ├── ServerApp.java
│       └── ServerContext.java
├── native/
│   └── linux-x86_64/
│       └── libblsjava.so   - built per-machine, not committed
├── network/         - UDP link stack (FairLoss -> Stubborn -> Perfect -> Authenticated)
├── config-dev.json  - zero faults, for development
└── config.json      - realistic faults, for testing
```

---

## Authors

| <div align="center"><a href="https://github.com/andrepires2211"><img src="https://avatars.githubusercontent.com/u/163666619?v=4" width="150px;" alt="André Pires"/></a><br/><strong>André Pires</strong><br/>116452<br/></div> | <div align="center"><a href="https://github.com/pedropmad"><img src="https://avatars.githubusercontent.com/u/163666619?v=4" width="150px;" alt="Pedro Duarte"/></a><br/><strong>Pedro Duarte</strong><br/>116390<br/></div> | <div align="center"><a href="https://github.com/tomasf18"><img src="https://avatars.githubusercontent.com/u/122024767?v=4" width="150px;" alt="Tomás Santos"/></a><br/><strong>Tomás Santos</strong><br/>116122<br/></div> |
| --- | --- | --- |