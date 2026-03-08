# DepChain - Dependable Blockchain

A permissioned blockchain system implementing the **BasicHotStuff** BFT consensus protocol with BLS12-381 threshold signatures, built on top of authenticated UDP communication links.

---

## Overview

The system tolerates up to `f` Byzantine faults among `n = 3f+1` replicas. Clients submit append requests and wait for `f+1` committed responses. The consensus layer uses the four-phase BasicHotStuff protocol (PREPARE -> PRE-COMMIT -> COMMIT -> DECIDE) with threshold signatures for quorum certificates.

---

## Requirements

| Dependency | Version |
|---|---|
| Java (OpenJDK) | 21 |
| Maven | 3.8+ |

---

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
mvn clean install
```

## Configuration

Two config files are provided:

| File | Purpose |
|---|---|
| `config-dev.json` | Zero fault injection, fast timeouts - use for development |
| `config.json` | Realistic fault injection (10% drop/duplicate/tamper) - use for testing |

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

### Using `test.sh` (recommended)

`test.sh` launches all servers and clients in a single `tmux` session. Run it from the `depchain/` directory:

```bash
./test.sh [options]
```

| Flag | Description | Default |
|---|---|---|
| `-f F` | Number of tolerated faults; starts `3F+1` servers | `1` |
| `-n N` | Number of clients to start | `1` |
| `-t` | Use `config-test.json` (fault injection enabled) | uses `config-dev.json` |
| `-c` | Recompile before starting | off |

**Examples:**

```bash
./test.sh                  # 4 servers, 1 client, dev config
./test.sh -c               # same but recompile first
./test.sh -f 2 -n 3        # 7 servers, 3 clients, dev config
./test.sh -t               # 4 servers, 1 client, test config (fault injection)
./test.sh -f 2 -n 2 -t -c  # 7 servers, 2 clients, test config, recompile
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

If no progress within `INITIAL_TIMEOUT_MS` (10s), all replicas advance to the next view and send `NEW_VIEW` to the next leader. Timeouts use **exponential backoff** (doubling after 2 consecutive failures, capped at 60s) to converge replica timers under asynchrony.

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