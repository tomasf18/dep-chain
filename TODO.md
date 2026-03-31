# TODO List

Important:
- Add logging options: nothing, info, debug

## TODO

- READ OPERATIONS bothe Native and ERC20 balanceOf (client should be able to ask for their token balance and the replica should reply with it, provided the replica response includes the information needed to verify the balance is correct)
 Clients should see their balance by requesting nodes for it. But replicas sending an entire chain of blocks is very inefficient since it can be huge. We must consider a better alternative to ensure the client receives the state and can verify it is correct (merkle proofs, or maybe a snapshot of the state at a certain block number, etc) **EVERY REPLICA KEEPS A MERKLE ROOT OF THE WORLD STATE, WHEN CLIENT WANTS TO VERIFY ITS BALANCE, THE MERKLE ROOT CAN BE PROVIDED AND IF IT IS EQUAL FOR f+1 REPLICAS, THE CLIENT CAN TRUST THE BALANCE** -> this might not work because the client might want to verify a balance that is not in the latest block, but in a previous one. In that case, we can provide the client with the block hash and the state hash of that block, and the client can verify that the state hash matches the one in the block, and then verify that the balance is correct according to that state hash. This way, we can ensure that the client can verify their balance without having to receive the entire chain of blocks.
**WE NEED TO FIND A WAY OF GIVING THE CLIENT A LIGHT WAY OF VERIFYING THE RESPONSE** -> pedro, pensa nesta merda
- add servers initial state to the genesis bloc
- add smart contract (runtime code, bytecode de runtime) state to genesis (a contract account includes: nonce, balance, storage, code)
- adapt stage 1 tests (adversary tests only, *go fetch them at the last stage 1 commit*) to stage 2 - **IMPORTANT: YOU SHOULD ALSO ADAPT THE PRUNING** 
- inlude "guaranteee that the f+1 received responses are actually identical" test
- after adapting to stage 2, remove stage 1 folder
- add tests that use the config-test.json configuration file, with the non-zero probabilities 


- check TODO-TESTS.md for more details on the tests that are still missing.

## Completed

- Approval Frontrunning attacks are handled (create tests)
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
