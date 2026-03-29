# TODO List

Important:
- Add logging options: nothing, info, debug

## Not started

- Byzantine, Security, and Integration Testing -> Prove the system satisfies the important Stage 2 guarantees.
    * Invalid signature tests
    * Replay / duplicate nonce tests
    * Double-spend attempts
    * Insufficient balance tests
    * Invalid gas parameter tests
    * Equal-fee ordering determinism tests
    * Repeated DECIDE idempotence tests
    * Byzantine leader proposing malformed tx/block
    * ERC-20 allowance frontrunning scenario
    * Contract deployment / call consistency across replicas


## To Verify

- ERC20 balanceOf (client should be able to ask for their token balance and the replica should reply with it, provided the replica response includes the information needed to verify the balance is correct)
 Clients should see their balance by requesting nodes for it. But replicas sending an entire chain of blocks is very inefficient since it can be huge. We must consider a better alternative to ensure the client receives the state and can verify it is correct (merkle proofs, or maybe a snapshot of the state at a certain block number, etc) **EVERY REPLICA KEEPS A MERKLE ROOT OF THE WORLD STATE, WHEN CLIENT WANTS TO VERIFY ITS BALANCE, THE MERKLE ROOT CAN BE PROVIDED AND IF IT IS EQUAL FOR f+1 REPLICAS, THE CLIENT CAN TRUST THE BALANCE** -> this might not work because the client might want to verify a balance that is not in the latest block, but in a previous one. In that case, we can provide the client with the block hash and the state hash of that block, and the client can verify that the state hash matches the one in the block, and then verify that the balance is correct according to that state hash. This way, we can ensure that the client can verify their balance without having to receive the entire chain of blocks.
- Approval Frontrunning attacks are handled (create tests)

## Doubts:
- For system liveness, is it enough to always have newViews after timeout, or is it necessary to always be proposing new blocks even without transactions?
- Why is it the client who defines the gas price? Shouldn't it be the EVM or a global variable of the system? 
ANS.: In an EVM-based blockchain, gas pricing is actually a hybrid system where the protocol sets a baseline, but the client chooses the final "bid" to ensure their transaction is processed. While it might seem like a global variable should suffice, the system uses a market-driven approach to manage network congestion and security. The blockchain does include a global variable called the Base Fee, which is automatically calculated by the protocol based on how busy the network was in the previous block. This ensures a predictable minimum cost for every user. However, the client (the user's wallet) defines an additional Priority Fee (or "tip"). This tip is necessary because the EVM has limited space per block; by allowing users to set their own price, the system creates an incentive auction. High-priority transactions can "outbid" others to be included immediately by validators, while low-priority users can choose to pay less and wait for a quieter time.
Ultimately, the client defines the price because only the user knows the economic value of their specific transaction. If the price were a strictly fixed global variable, the network would have no way to prioritize critical actions during high traffic, making it vulnerable to spam and preventing a functional market for network space.

## Completed

(ERC20 = IST Coin -> see "Native currency vs Tokens" section below)
- ERC20 token transfers (client should be able to ask to transfer tokens to another account, and the replica should execute it if the client has enough balance)
- ERC20 increaseAllowance (client should be able to ask to increase the allowance they have given to another account, and the replica should execute it)
- ERC20 decreaseAllowance (client should be able to ask to decrease the allowance they have given to another account, and the replica should execute it if the client has enough allowance)
- ERC20 transferFrom (client should be able to ask to transfer tokens on behalf of another account, and the replica should execute it if the client has enough allowance)
For the previous points, pay attention to:
- The returnData should be used when required to send response data to the client. For example, in the case of balanceOf, it should carry the client's balance in the reply.
- Separate 2 Client API calls for getting balance: one that returns the balance in the native currency (DepCoin) and another that returns the balance of a specific token (ERC-20). This is because they are stored differently in the world state and require different logic to retrieve.

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






Other stuff:

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