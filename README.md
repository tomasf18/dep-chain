# DepChain - Dependable Blockchain - Group 5

A permissioned blockchain system implementing the BasicHotStuff BFT consensus protocol with BLS12-381 threshold signatures, built on top of authenticated UDP communication links.

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

The project submission includes the complete source tree plus the runtime assets needed to build and run the demo applications and tests, including:

- the Maven modules under `depchain/common`, `depchain/network`, `depchain/core`, `depchain/client`, and `depchain/tests`
- the Solidity contracts and generated ABI/bin artifacts under `depchain/solidity`
- the keystore material used by the servers, clients, and tests
- the BLS native libraries under `depchain/core/native` and `depchain/tests/native`
- the configuration files under `depchain/config`

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

# TODOOOOOOOOOOOOO

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