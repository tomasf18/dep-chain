# DepChain Stage 2 — Parallelization Guide

## Dependency Graph

```
Step 1 (Solidity ERC-20)  ──────┐
                                 ├──→ Step 4 (EVM Integration) ──┐
Step 2 (Account/State)    ──────┤                                ├──→ Step 6 (Consensus) ──→ Step 7 (Client) ──→ Step 8 (Tests)
                                 ├──→ Step 3 (Tx & Gas)    ──────┤
                                 └──→ Step 5 (Blocks)      ──────┘
```

## Parallel Work Phases

### Phase A — Foundations (fully parallel)
| Stream        | Step                         | Description                                      |
|---------------|------------------------------|--------------------------------------------------|
| Stream 1      | Step 1: Solidity ERC-20      | Write & compile the frontrunning-resistant token  |
| Stream 2      | Step 2: Account Model/State  | EOA/Contract accounts, WorldState, genesis loader |

These two are **completely independent** — no shared code or data structures.

### Phase B — Core Mechanics (partially parallel)
| Stream        | Step                         | Description                                       |
|---------------|------------------------------|----------------------------------------------------|
| Stream 1      | Step 3: Tx & Gas             | Transaction object, gas calc, tx validation        |
| Stream 2      | Step 5: Block Structure      | Block model, ordering, persistence, chain linking  |

Both depend on Step 2 (account model) but are **independent of each other**.
Step 3 and Step 5 can be developed simultaneously once Step 2 is complete.

### Phase C — Integration (sequential)
| Order | Step                         | Description                                         |
|-------|------------------------------|------------------------------------------------------|
| 1     | Step 4: EVM Integration      | Requires Steps 1 + 2 + 3 (contract + accounts + tx) |
| 2     | Step 6: Consensus            | Requires Steps 3 + 4 + 5 (tx + EVM + blocks)        |
| 3     | Step 7: Client Refactoring   | Requires Step 6 (consensus must handle transactions) |

### Phase D — Validation
| Step                         | Description                                       |
|------------------------------|---------------------------------------------------|
| Step 8: Byzantine Testing    | Requires everything; can start writing test stubs earlier |

## Summary

| Phase   | Steps    | Parallelism | Bottleneck                        |
|---------|----------|-------------|-----------------------------------|
| Phase A | 1, 2     | Full        | None — start both immediately     |
| Phase B | 3, 5     | Full        | Blocked on Step 2 completion      |
| Phase C | 4, 6, 7  | Sequential  | Each depends on the previous      |
| Phase D | 8        | N/A         | Blocked on all steps; stubs early |

## Key Takeaway

The **maximum parallelism** is 2 concurrent streams. The critical path is:

> Step 2 → Step 3 → Step 4 → Step 6 → Step 7 → Step 8

Step 1 (Solidity contract) and Step 5 (block structure) run as **side streams** that merge into the critical path at Step 4 and Step 6, respectively.
