# DepChain Stage 2 — Implementation Plan

## Step 1: ERC-20 Smart Contract (Solidity)
- Write a frontrunning-resistant ERC-20 contract
  - Name: "IST Coin", Symbol: "IST", Decimals: 2, Total Supply: 100,000,000
- Replace `approve()` with `increaseAllowance()` / `decreaseAllowance()` to prevent approval frontrunning
- Use OpenZeppelin ERC-20 as reference
- Compile to EVM bytecode (deployment + runtime)
- **Dependencies:** None

## Step 2: Account Model & World State
- Define two account types:
  - **EOA**: address (derived from public key), balance (DepCoin), nonce
  - **Contract Account**: address, balance, code (EVM bytecode), storage (key-value)
- Ethereum-style address derivation from ECDSA public keys
- `WorldState` class wrapping Besu's `SimpleWorld` for state management
- Genesis block loader (JSON): initial balances, nonces, ERC-20 deployment tx
- Add Besu Maven dependencies (evm, datatypes, tuweni-bytes, web3j crypto)
- **Dependencies:** None

## Step 3: Transaction & Gas Mechanism
- Design transaction object: `from`, `to`, `value`, `input/data`, `gas_price`, `gas_limit`, `nonce`, `signature`
- Gas fee calculation: `min(gas_price * gas_limit, gas_price * gas_used)`
- If `gas_used > gas_limit` → tx aborted, gas **not** refunded
- Fees paid in DepCoin (native currency), credited to block proposer/leader
- Transaction validation:
  - ECDSA signature verification
  - Nonce check (must match sender's current nonce)
  - Balance check (sender must have enough DepCoin for gas + value)
  - `gas_price > 0` and `gas_limit > 0`
- **Dependencies:** Step 2

## Step 4: EVM Integration (Besu)
- EVM executor for contract **deployment** (`to = null`, `input = deployment bytecode`)
- EVM executor for contract **calls** (function selector + ABI-encoded params)
- Native DepCoin transfers (balance updates, no EVM needed)
- Tracer-based return data extraction from EVM memory/stack
- **Dependencies:** Steps 1, 2, 3

## Step 5: Block Structure & Persistence
- Block fields: hash, previous_hash, list of transactions, resulting world state
- Transaction ordering within block: sorted by fee descending (highest fee first)
- JSON-based block persistence (same format as genesis block)
- Block chain linking (each block points to parent via `previous_block_hash`)
- **Dependencies:** Steps 2, 3

## Step 6: Consensus Integration
- Refactor HotStuff to propose/decide **blocks of transactions** instead of single string commands
- Leader collects transactions from mempool, orders by fee, builds block proposal
- On DECIDE phase: execute all transactions against world state, persist block
- Update protobuf messages to carry transaction data
- **Dependencies:** Steps 3, 4, 5

## Step 7: Client Refactoring
- Client sends **transactions**: transfer DepCoin, call contract, deploy contract
- Client signs each transaction with ECDSA private key
- Client can query state: balance lookups, contract read calls
- Byzantine client tolerance on server side:
  - Validate all client signatures
  - Check nonces (prevent replay)
  - Check balances (prevent overdraft)
- **Dependencies:** Steps 3, 6

## Step 8: Byzantine Testing
- Approval frontrunning attack demo (and proof that `increaseAllowance` blocks it)
- Double-spend attempts (same nonce, insufficient balance)
- Byzantine client-server collusion scenarios
- Invalid signature / replay / nonce manipulation tests
- Gas manipulation attacks (zero gas_price, zero gas_limit)
- **Dependencies:** All above
