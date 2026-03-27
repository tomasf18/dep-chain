# TODO List

## Not started

- Order transactions by fee, but for the same client order by nonce
- Clients should see their balance by requesting nodes for it. Sending an entire chain of blocks is very inefficient since it can be huge. Consider a better alternative to ensure the client receives the state and can verify it is correct (merkle proofs, or maybe a snapshot of the state at a certain block number, etc)
- Separate 2 Client API calls for getting balance: one that returns the balance in the native currency (DepCoin) and another that returns the balance of a specific token (ERC-20). This is because they are stored differently in the world state and require different logic to retrieve.
- The returnData should be used when required to send response data to the client. For example, in the case of balanceOf, it should carry the client's balance in the reply

## To Verify

- When just transferring DepCoin, should we create an EVM or just change the SimpleWorld directly? -> No, DepCoin does not require executing smart contract code so the EVM does not need to be invoked
- It's okay if a replica receives a transaction in a block from the leader that it hasn't yet received from the client

## Doubts:
- For system liveness, is it enough to always have newViews after timeout, or is it necessary to always be proposing new blocks even without transactions?
- Why is it the client who defines the gas price? Shouldn't it be the EVM or a global variable of the system? 
ANS.: In an EVM-based blockchain, gas pricing is actually a hybrid system where the protocol sets a baseline, but the client chooses the final "bid" to ensure their transaction is processed. While it might seem like a global variable should suffice, the system uses a market-driven approach to manage network congestion and security. The blockchain does include a global variable called the Base Fee, which is automatically calculated by the protocol based on how busy the network was in the previous block. This ensures a predictable minimum cost for every user. However, the client (the user's wallet) defines an additional Priority Fee (or "tip"). This tip is necessary because the EVM has limited space per block; by allowing users to set their own price, the system creates an incentive auction. High-priority transactions can "outbid" others to be included immediately by validators, while low-priority users can choose to pay less and wait for a quieter time.
Ultimately, the client defines the price because only the user knows the economic value of their specific transaction. If the price were a strictly fixed global variable, the network would have no way to prioritize critical actions during high traffic, making it vulnerable to spam and preventing a functional market for network space.

## Completed

- Clients need to sign requests and replicas need to verify
- Application-level sequence numbers to prevent replay attacks (application tracks client state: "Paulo has 10 transactions")

- Line in verifyQC
- 2f+1 fix code and report
- Guarantee that the f+1 received responses are actually identical (use hash)

- Management of tree pruning for conflicting values (when locked, prune the non-locked ones)
- Create tests where this happens (conflicting values)

### Request Tracking Architecture
Before, the server rejected any client request whose requestId was not strictly greater than the highest seen one. This was too brittle over UDP and incompatible with the richer transaction flow needed in Stage 2. A valid delayed request could be discarded just because a later one arrived first.

The solution: Changed how client requests are tracked on the server
- In the message handler, requests with lower sequence numbers are no longer ignored
- The message handler now has a callback to the coordinator that is called when a request is committed
- The message handler marks the request as executed and responds to the client
- This is necessary to prevent replay attacks and to guarantee that the client receives a response even if the request is re-proposed multiple times (for example, if the leader fails after proposing but before commit)




# Native currency vs Tokens 
Native currency

* What it is: The "built-in" currency defined at the protocol level (e.g., ETH). In the genesis block, its initial state is defined by the balance field of an account, handled mathematically as a 256-bit word to ensure high-precision arithmetic.
* What it's used for: Primarily used to pay for gas fees (the cost of computation) and as the base layer of value for the network.
* Motivation for having them: It acts as the "fuel" or "incentive" for the network. Without a native currency, there is no way to prioritize transactions or reward the nodes/validators that maintain the blockchain's security.

Tokens

* What they are: Digital assets created via smart contracts (like ERC-20) rather than the core protocol. Unlike native balances stored in the account state, token balances are stored in the contract's storage slots (mapping addresses to amounts).
* What they're used for: Specialized utility such as stablecoins (USDC for price stability), governance (voting rights), utility (accessing specific app features), or representing real-world assets.
* Motivation for having them: They allow for programmable value. While the native currency is "dumb" money with fixed rules, tokens can have complex logic (e.g., a token that can only be sent to verified users or a token that automatically pays dividends).

Final comparison

| Feature | Native Currency | Tokens |
|---|---|---|
| Storage Location | Account State (balance field) | Smart Contract Storage (as data "words") |
| Transaction Fee | Used to pay for the gas | Requires native currency to move |
| Logic | Hardcoded in the protocol | Customizable via Smart Contracts |
| Analogy | The Electricity (Infrastructure) | The Appliances (Applications) |






I need to find the following bug in the code: whenever a transation is committed, I run printWorldState() to see the changes in the world state on each replica. However, I noticed that the changes are not equal in all replicas, namely, the HotStuff leader who proposed the block does not see its own account details, which means the world state diverges from the others. Another bad thing that is happening is that the line "Proposer account 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd does not exist in world state. Creating it to credit fees." is being printed 2 times in the replicas. 
Analyse the following outputs and then the code to find the bugs:

[REPLICA 0]
```
(env) [15:28:45] tomas@tomas-ASUS /home/tomas/Workstation/uni/dep-chain/depchain/core [SIGINT] 
> mvn exec:java -Dexec.args='../config/config-dev.json s0'                                       (base) 
[INFO] Scanning for projects...
[INFO] 
[INFO] -----------------------< ist.depchain.core:core >-----------------------
[INFO] Building core 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- exec:3.6.3:java (default-cli) @ core ---
[AUTHENTICATOR | INFO] - Derived session keys with 6 peers from static keys.
[SERVER_CONTEXT] Genesis block loaded with 1 transactions 
IST contract bootstrapped at 0x9999999999999999999999999999999999999999

=== After Deployment ===
InitialTokenHolder Account
  Address: 0x1000000000000000000000000000000000000001
  Balance: 1000000000000000000000 DepCoin
  Nonce: 0
  Code size: 0
Contract Account
  Address: 0x9999999999999999999999999999999999999999
  Balance: 0 DepCoin
  Nonce: 0
  Code size: 2283
client2 Account
  Address: 0x172bf398d2a931323199521625f471fb1c28879a
  Balance: 500000000 DepCoin
  Nonce: 0
  Code size: 0
client1 Account
  Address: 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9
  Balance: 1000000000 DepCoin
  Nonce: 0
  Code size: 0

=== ERC-20 Read Calls ===
name():        IST Coin
symbol():      IST
decimals():    2
totalSupply(): 10000000000
balanceOf(initialTokenHolder): 10000000000

[BLS | INFO] - BLS12-381 initialized
[BLS | INFO] - Loaded BLS keys for replica index 1
[SERVER_APP | INFO] Successfully started
[MESSAGE_HANDLER | INFO] Accepted tx request RequestKey{clientId='client1', requestId=1} from=0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 nonce=0
[COORDINATOR] Checking fees for next batch in mempool...
[COORDINATOR] Current batch total fees: 63000 / 63000
World State:
- 0x1000000000000000000000000000000000000001 (Unknown): balance=1000000000000000000000, nonce=0
- 0x172bf398d2a931323199521625f471fb1c28879a (client2): balance=500100000, nonce=0
- 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 (client1): balance=999837000, nonce=1

[COORDINATOR] Committed block #1 with 1 txs, hash=92e999f5f68f7f9db39c630542d3df0a69c2871ffa857a0a7705dfc3ad732dfa, stateHash=ee526669746b4faf8fd28169e95d62c06bbf888f3c32ab2e38b64e5382b54e7f
```

[REPLICA 1]
```
[15:28:46] tomas@tomas-ASUS /home/tomas/Workstation/uni/dep-chain/depchain/core [SIGINT] 
> mvn exec:java -Dexec.args='../config/config-dev.json s1'                                      (base) 
[INFO] Scanning for projects...
[INFO] 
[INFO] -----------------------< ist.depchain.core:core >-----------------------
[INFO] Building core 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- exec:3.6.3:java (default-cli) @ core ---
[AUTHENTICATOR | INFO] - Derived session keys with 6 peers from static keys.
[SERVER_CONTEXT] Genesis block loaded with 1 transactions 
IST contract bootstrapped at 0x9999999999999999999999999999999999999999

=== After Deployment ===
InitialTokenHolder Account
  Address: 0x1000000000000000000000000000000000000001
  Balance: 1000000000000000000000 DepCoin
  Nonce: 0
  Code size: 0
Contract Account
  Address: 0x9999999999999999999999999999999999999999
  Balance: 0 DepCoin
  Nonce: 0
  Code size: 2283
client2 Account
  Address: 0x172bf398d2a931323199521625f471fb1c28879a
  Balance: 500000000 DepCoin
  Nonce: 0
  Code size: 0
client1 Account
  Address: 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9
  Balance: 1000000000 DepCoin
  Nonce: 0
  Code size: 0

=== ERC-20 Read Calls ===
name():        IST Coin
symbol():      IST
decimals():    2
totalSupply(): 10000000000
balanceOf(initialTokenHolder): 10000000000

[BLS | INFO] - BLS12-381 initialized
[BLS | INFO] - Loaded BLS keys for replica index 2
[SERVER_APP | INFO] Successfully started
[MESSAGE_HANDLER | INFO] Accepted tx request RequestKey{clientId='client1', requestId=1} from=0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 nonce=0
[WARN] Proposer account 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd does not exist in world state. Creating it to credit fees.
[WARN] Proposer account 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd does not exist in world state. Creating it to credit fees.
World State:
- 0x1000000000000000000000000000000000000001 (Unknown): balance=1000000000000000000000, nonce=0
- 0x172bf398d2a931323199521625f471fb1c28879a (client2): balance=500100000, nonce=0
- 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd (s0): balance=63000, nonce=0
- 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 (client1): balance=999837000, nonce=1

[COORDINATOR] Committed block #1 with 1 txs, hash=0e46c38ac2245f416c601010405efdb095b5d8ccdf9eb9bdb34ea9e2f10838de, stateHash=577d4167edb1f0919604522fba637bb3ba1068a63901d2ce496cc5eb5ba5643c
```

[REPLICA 2]
```
[15:28:45] tomas@tomas-ASUS /home/tomas/Workstation/uni/dep-chain/depchain/core [SIGINT] 
> mvn exec:java -Dexec.args='../config/config-dev.json s2'                                   (base) 
[INFO] Scanning for projects...
[INFO] 
[INFO] -----------------------< ist.depchain.core:core >-----------------------
[INFO] Building core 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- exec:3.6.3:java (default-cli) @ core ---
[AUTHENTICATOR | INFO] - Derived session keys with 6 peers from static keys.
[SERVER_CONTEXT] Genesis block loaded with 1 transactions 
IST contract bootstrapped at 0x9999999999999999999999999999999999999999

=== After Deployment ===
InitialTokenHolder Account
  Address: 0x1000000000000000000000000000000000000001
  Balance: 1000000000000000000000 DepCoin
  Nonce: 0
  Code size: 0
Contract Account
  Address: 0x9999999999999999999999999999999999999999
  Balance: 0 DepCoin
  Nonce: 0
  Code size: 2283
client2 Account
  Address: 0x172bf398d2a931323199521625f471fb1c28879a
  Balance: 500000000 DepCoin
  Nonce: 0
  Code size: 0
client1 Account
  Address: 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9
  Balance: 1000000000 DepCoin
  Nonce: 0
  Code size: 0

=== ERC-20 Read Calls ===
name():        IST Coin
symbol():      IST
decimals():    2
totalSupply(): 10000000000
balanceOf(initialTokenHolder): 10000000000

[BLS | INFO] - BLS12-381 initialized
[BLS | INFO] - Loaded BLS keys for replica index 3
[SERVER_APP | INFO] Successfully started
[MESSAGE_HANDLER | INFO] Accepted tx request RequestKey{clientId='client1', requestId=1} from=0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 nonce=0
[COORDINATOR] Checking fees for next batch in mempool...
[COORDINATOR] Current batch total fees: 63000 / 63000
[WARN] Proposer account 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd does not exist in world state. Creating it to credit fees.
[WARN] Proposer account 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd does not exist in world state. Creating it to credit fees.
World State:
- 0x1000000000000000000000000000000000000001 (Unknown): balance=1000000000000000000000, nonce=0
- 0x172bf398d2a931323199521625f471fb1c28879a (client2): balance=500100000, nonce=0
- 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd (s0): balance=63000, nonce=0
- 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 (client1): balance=999837000, nonce=1

[COORDINATOR] Committed block #1 with 1 txs, hash=0e46c38ac2245f416c601010405efdb095b5d8ccdf9eb9bdb34ea9e2f10838de, stateHash=577d4167edb1f0919604522fba637bb3ba1068a63901d2ce496cc5eb5ba5643c
```

[REPLICA 3]
```
[15:28:44] tomas@tomas-ASUS /home/tomas/Workstation/uni/dep-chain/depchain/core [SIGINT] 
> mvn exec:java -Dexec.args='../config/config-dev.json s3'                                   (base) 
[INFO] Scanning for projects...
[INFO] 
[INFO] -----------------------< ist.depchain.core:core >-----------------------
[INFO] Building core 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- exec:3.6.3:java (default-cli) @ core ---
[AUTHENTICATOR | INFO] - Derived session keys with 6 peers from static keys.
[SERVER_CONTEXT] Genesis block loaded with 1 transactions 
IST contract bootstrapped at 0x9999999999999999999999999999999999999999

=== After Deployment ===
InitialTokenHolder Account
  Address: 0x1000000000000000000000000000000000000001
  Balance: 1000000000000000000000 DepCoin
  Nonce: 0
  Code size: 0
Contract Account
  Address: 0x9999999999999999999999999999999999999999
  Balance: 0 DepCoin
  Nonce: 0
  Code size: 2283
client2 Account
  Address: 0x172bf398d2a931323199521625f471fb1c28879a
  Balance: 500000000 DepCoin
  Nonce: 0
  Code size: 0
client1 Account
  Address: 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9
  Balance: 1000000000 DepCoin
  Nonce: 0
  Code size: 0

=== ERC-20 Read Calls ===
name():        IST Coin
symbol():      IST
decimals():    2
totalSupply(): 10000000000
balanceOf(initialTokenHolder): 10000000000

[BLS | INFO] - BLS12-381 initialized
[BLS | INFO] - Loaded BLS keys for replica index 4
[SERVER_APP | INFO] Successfully started
[MESSAGE_HANDLER | INFO] Accepted tx request RequestKey{clientId='client1', requestId=1} from=0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 nonce=0
[WARN] Proposer account 0xdb710290c98c770c5ee6b312ea42b89cdbd23cc2 does not exist in world state. Creating it to credit fees.
[COORDINATOR] Checking fees for next batch in mempool...
[COORDINATOR] Current batch total fees: 63000 / 63000
[WARN] Proposer account 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd does not exist in world state. Creating it to credit fees.
[WARN] Proposer account 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd does not exist in world state. Creating it to credit fees.
World State:
- 0x1000000000000000000000000000000000000001 (Unknown): balance=1000000000000000000000, nonce=0
- 0x172bf398d2a931323199521625f471fb1c28879a (client2): balance=500100000, nonce=0
- 0x2b79c34225c4e0aeb709dbe420d328d6d051e4bd (s0): balance=63000, nonce=0
- 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 (client1): balance=999837000, nonce=1

[COORDINATOR] Committed block #1 with 1 txs, hash=0e46c38ac2245f416c601010405efdb095b5d8ccdf9eb9bdb34ea9e2f10838de, stateHash=577d4167edb1f0919604522fba637bb3ba1068a63901d2ce496cc5eb5ba5643c
```

[Client 1]
```
[15:32:14] tomas@tomas-ASUS /home/tomas/Workstation/uni/dep-chain/depchain/client  
> mvn exec:java -Dexec.args='../config/config-dev.json client1'                              (base) 
[INFO] Scanning for projects...
[INFO] 
[INFO] ---------------------< ist.depchain.client:client >---------------------
[INFO] Building client 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- exec:3.6.3:java (default-cli) @ client ---
[AUTHENTICATOR | INFO] - Derived session keys with 5 peers from static keys.
[INFO] Successfully started:
    - Client ID: client1
    - Blockchain address: 0xfe37d77266b312ca364bd3f9386e1df4d193e9d9

=== DepChain Client ===
0 - Check balance
1 - Submit native transfer
2 - ERC20 transfer
3 - ERC20 increaseAllowance
4 - ERC20 decreaseAllowance
5 - ERC20 transferFrom
exit - Quit
> 1
Destination address (0x...): 0x172bf398d2a931323199521625f471fb1c28879a
Value: 100000
Gas price: 3
Gas limit: 21000
[SENT] reqId=1 from=0xfe37d77266b312ca364bd3f9386e1df4d193e9d9 to=0x172bf398d2a931323199521625f471fb1c28879a value=100000 nonce=0
[✓] (1, s0): ACCEPTED - transaction is being processed
[ACCEPTED] reqId=1 nonce=0 - transaction is now being processed
[SUBMITTED] native tx reqId=1 nonce=0

=== DepChain Client ===
0 - Check balance
1 - Submit native transfer
2 - ERC20 transfer
3 - ERC20 increaseAllowance
4 - ERC20 decreaseAllowance
5 - ERC20 transferFrom
exit - Quit
> 
```