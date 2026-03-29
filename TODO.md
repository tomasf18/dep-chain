# TODO List

Important:
- Add logging options: nothing, info, debug

## Almost done

(ERC20 = IST Coin -> see "Native currency vs Tokens" section below)
- ERC20 token transfers (client should be able to ask to transfer tokens to another account, and the replica should execute it if the client has enough balance)
- ERC20 increaseAllowance (client should be able to ask to increase the allowance they have given to another account, and the replica should execute it)
- ERC20 decreaseAllowance (client should be able to ask to decrease the allowance they have given to another account, and the replica should execute it if the client has enough allowance)
- ERC20 transferFrom (client should be able to ask to transfer tokens on behalf of another account, and the replica should execute it if the client has enough allowance)
- ERC20 balanceOf (client should be able to ask for their token balance and the replica should reply with it, provided the replica response includes the information needed to verify the balance is correct)
- Native balance retrieval (client should be able to ask for their balance and the replica should reply with it)

For the previous points, pay attention to:
- The returnData should be used when required to send response data to the client. For example, in the case of balanceOf, it should carry the client's balance in the reply.
- Clients should see their balance by requesting nodes for it. But replicas sending an entire chain of blocks is very inefficient since it can be huge. We must consider a better alternative to ensure the client receives the state and can verify it is correct (merkle proofs, or maybe a snapshot of the state at a certain block number, etc) **EVERY REPLICA KEEPS A MERKLE ROOT OF THE WORLD STATE, WHEN CLIENT WANTS TO VERIFY ITS BALANCE, THE MERKLE ROOT CAN BE PROVIDED AND IF IT IS EQUAL FOR f+1 REPLICAS, THE CLIENT CAN TRUST THE BALANCE** -> this might not work because the client might want to verify a balance that is not in the latest block, but in a previous one. In that case, we can provide the client with the block hash and the state hash of that block, and the client can verify that the state hash matches the one in the block, and then verify that the balance is correct according to that state hash. This way, we can ensure that the client can verify their balance without having to receive the entire chain of blocks.
- Separate 2 Client API calls for getting balance: one that returns the balance in the native currency (DepCoin) and another that returns the balance of a specific token (ERC-20). This is because they are stored differently in the world state and require different logic to retrieve.

## Not started

- If we are a replica should we validate if a transaction of depchain is possible due to account balances after or before the consensus for the block is reached? Could we accept a block with that transaction and when we are executing it if it fails we simple don't change the state and still make the sender pay the gas fee? PROFESSOR ANSWER: You should reason about the implications of the various design options and explain your choices in the report and in the discussion.

## To Verify

- When just transferring DepCoin, should we create an EVM or just change the SimpleWorld directly? -> PROFESSOR ANSWER: No, DepCoin does not require executing smart contract code so the EVM does not need to be invoked
- Approval Frontrunning attacks are handled (create tests)

## Doubts:
- For system liveness, is it enough to always have newViews after timeout, or is it necessary to always be proposing new blocks even without transactions?
- Why is it the client who defines the gas price? Shouldn't it be the EVM or a global variable of the system? 
ANS.: In an EVM-based blockchain, gas pricing is actually a hybrid system where the protocol sets a baseline, but the client chooses the final "bid" to ensure their transaction is processed. While it might seem like a global variable should suffice, the system uses a market-driven approach to manage network congestion and security. The blockchain does include a global variable called the Base Fee, which is automatically calculated by the protocol based on how busy the network was in the previous block. This ensures a predictable minimum cost for every user. However, the client (the user's wallet) defines an additional Priority Fee (or "tip"). This tip is necessary because the EVM has limited space per block; by allowing users to set their own price, the system creates an incentive auction. High-priority transactions can "outbid" others to be included immediately by validators, while low-priority users can choose to pay less and wait for a quieter time.
Ultimately, the client defines the price because only the user knows the economic value of their specific transaction. If the price were a strictly fixed global variable, the network would have no way to prioritize critical actions during high traffic, making it vulnerable to spam and preventing a functional market for network space.

## Completed

- Order transactions by fee, **but for the same client order by nonce - "the transactions of each client are ordered by its nonce/submission order"**
- native transfers working with EVM execution
- tests for native transfers and state convergenge among replicas
- Clients need to sign requests and replicas need to verify
- Application-level sequence numbers to prevent replay attacks (application tracks client state: "Paulo has 10 transactions")
- Line in verifyQC
- 2f+1 fix code and report
- Guarantee that the f+1 received responses are actually identical (use hash)
- Management of tree pruning for conflicting values (when locked, prune the non-locked ones)
- Create tests where this happens (conflicting values)






----






Other stuff, just to recall for the report:

### Request Tracking 
Before, the server rejected any client request whose requestId was not strictly greater than the highest seen one. This was too brittle over UDP and incompatible with the richer transaction flow needed in Stage 2. A valid delayed request could be discarded just because a later one arrived first.

The solution: Changed how client requests are tracked on the server
- In the message handler, requests with lower sequence numbers are no longer ignored
- The message handler now has a callback to the coordinator that is called when a request is committed
- The message handler marks the request as executed and responds to the client
- This is necessary to prevent replay attacks and to guarantee that the client receives a response even if the request is re-proposed multiple times (for example, if the leader fails after proposing but before commit)

---

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


---

# HotStuff Patchws

Just for ref for the report: implementation changes made to stabilize the HotStuff flow 

## 1 - the fee-threshold batch selection was made deterministic and order-independent

### Files changed
- CommandMempool
- BasicHotStuffCoordinator
- CommandMempoolTest

### What changed
- Added fee-aware batch selection in the mempoool.
- The coordinator now selects the highest-fee sufficient batch from the full pending set instead of trusting fifo order
- Fee totals are now computed with BigInteger instead of th brittle "long" conversion from raw protobuf bytes
- Added a test for a high-fee transaction sitting behind lower-fee ones in the queue

### Problem solved
- The proposer was sometimes waiting even though the mempool already contained enough fees
- FIFO ordering could block a fee-sufficient transaction behind lower-fee transactions
- The previous fee calculation could misreport totals and even produce negative values

## 2 - Late DECIDE handling is now view-tolerant

### File changed
- BasicHotStuffCoordinator

### What changed
- DECIDE messages are no longer discarded just because the replica has already advanced to a later view
- The decide handler now validates the message using the decide message’s own view and leader
- If a valid decide arrives for an earlier committed view, the replica can still execute it
- A lagging replica now fast-forwards to the decided view’s successor before continuing

### Problem solved
- A slower replica could receive the client transactions, advance views due to timeouts, and then ignore the already-decided block
- This caused replicas to diverge in execution even though consensus had completed

### result
- Valid decided blocks are executed even if the replica has already moved on
- The node no longer gets stuck one view behind the rest of the quorum

## 3- out-of-order HotStuff messages are now buffered and recovered

### File changed
- BasicHotStuffCoordinator

### what changed
- valid PREPARE blocks are cached even if they arrive while a replica is behind or ahead of the current view
- DECIDE messages are buffered if the block is not yet known in the local tree
- once the block arrives, a pending DECIDE is executed immediately
- a replica can fast-forward to the decided view’s successor after executing a valid DECIDE

### problem solved
- a replica could fall behind if it timed out and advanced views before receiving the matching PREPARE/DECIDE pair
- the block would then never be executed locally even though consensus had completed

### result
- lagging replicas can catch up instead of getting stuck permanently behind
- out-of-order message delivery is handled more safely

## 4 - Null and stale consensus inputs are now better handled

### File changed
- BasicHotStuffCoordinator

### What changed
- Added null checks before using blocks fetched from the HotStuff tree
- PRE-COMMIT and COMMIT handlers now refuse to move QC state backwards when older QCs arrive late
- Duplicate votes from the same sender are ignored instead of silently overwriting existing votes

### Problem solved
- Byzantine or delayed messages could trigger null-pointer crashes
- Older QCs could overwrite newer local state
- Duplicate sender votes could hide equivocation and weaken traceability
- Single-thread wakeups could miss the right waiting path under contention

### result
- The replica is more resilient to late, duplicate, malformed and reordered consensus messages
- The protocol state is less likely to diverge under stress or Byzantine behavior