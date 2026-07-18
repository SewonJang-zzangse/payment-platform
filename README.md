# Cross-Border Payment Platform

A reference implementation of a multi-currency, cross-border money-movement backend,
built with **Kotlin, Temporal, and PostgreSQL**. The project focuses on the three
problems that make payment systems genuinely hard:

1. **A double-entry ledger where money cannot leak** — balance invariants enforced
   at both the application and database level, idempotent postings, append-only
   reversals.
2. **Saga-based transfer orchestration** — no distributed transactions; instead,
   layered idempotency, explicit compensation, and durable workflow state.
3. **Payment-rail integration and reconciliation** — external rails are treated as
   unreliable, `UNKNOWN` is a first-class outcome, and reconciliation against
   settlement records is the final source of truth.

## Design principles

**Money is integer minor units, never floats.** All amounts are `Long` minor units
paired with an ISO-4217 currency code. Cross-currency arithmetic is rejected at the
type level.

**A balance is derived, not stored.** Every movement of money is a balanced journal
entry (per-currency posting sums equal zero). This single invariant guarantees money
is never created or destroyed. Corrections are reversals — the ledger never deletes.

**Exactly-once *effect*, not exactly-once delivery.** Delivery guarantees are
impossible across process boundaries; the achievable goal is at-least-once delivery
combined with idempotent handling. Idempotency lives at three layers: API keys,
deterministic per-step ledger references, and a unique constraint in the database
as the last line of defense.

**Timeout is not failure.** When a payout call to an external rail times out, the
money may or may not have left. Retrying risks a double payout; refunding risks a
double loss. The system parks the funds in a settlement account, keeps the workflow
durably suspended (`Workflow.await`), and lets reconciliation against the rail's
settlement records decide the outcome.

**Asymmetric retry policy.** Idempotent activities retry automatically. The single
money-moving call (`initiatePayout`) is capped at one attempt and its three-state
result (`ACCEPTED / REJECTED / UNKNOWN`) is handled explicitly.

## Architecture

```
transfer/temporal/   Temporal workflow (durable saga; UNKNOWN -> await -> signal)
        │  delegates to
transfer/            Pure saga logic (TransferOrchestrator: the workflow's testable twin)
        │  uses
ledger/  rails/      Ledger (In-Memory / JDBC-Postgres) · rail adapters (anti-corruption layer)
        │
money/               Integer minor-unit value object
```

`TransferOrchestrator` (pure) and `TransferWorkflowImpl` (Temporal) execute the same
saga sequence. The former unit-tests the entire state machine without the SDK; the
latter adds durability and signal-based resumption.

### Transfer lifecycle

```
QUOTED -> RISK_CHECK -> FUNDS_IN -> [CONVERTED] -> PAID_OUT
                     \-> REJECTED                \-> PAYOUT_UNKNOWN -> (reconciliation)
                                                      -> PAID_OUT | REFUNDED
```

## Project layout

| Path | Contents |
|---|---|
| `money/` | `Money` value object: integer minor units, currency-safe arithmetic |
| `ledger/` | `Ledger` contract, in-memory and JDBC (Postgres) implementations |
| `rails/` | `RailAdapter` abstraction with capability flags and 3-state payout results; mock rail with forced failure modes |
| `transfer/` | Saga orchestration, activities with deterministic idempotency keys |
| `transfer/temporal/` | Temporal workflow, activity gateway, worker bootstrap |
| `reconciliation/` | Settlement matching, UNKNOWN resolution, break detection |
| `src/main/resources/db/schema.sql` | Postgres schema with a DB-side balance-enforcing constraint trigger |

## Build & run

Requires JDK 21. Gradle 8.12+ (or generate the wrapper once with a local Gradle).

```bash
./gradlew test      # ledger, saga, and reconciliation test suites
./gradlew build
./gradlew run       # starts the Temporal worker (requires a local Temporal server)
```

Local Temporal server: `temporal server start-dev` (Temporal CLI).
Initialize Postgres with `src/main/resources/db/schema.sql`.

## Testing strategy

The test suites assert the properties that matter in a money system:

- **No leakage:** after every scenario — success, rejection, timeout, retry —
  per-currency totals across all accounts are exactly zero.
- **UNKNOWN handling:** a timed-out payout is neither retried nor refunded; funds
  remain isolated in the settlement account until reconciliation resolves it in
  either direction.
- **Retry safety:** executing the same transfer twice produces exactly one nostro
  outflow.
- **Break detection:** settlement records with no matching internal transfer are
  surfaced for manual investigation instead of being silently absorbed.

## Roadmap

- Transactional outbox + Kafka: publish ledger events atomically with DB commits
  (CDC-style relay), consumed by reconciliation and analytics
- Testcontainers integration tests for `JdbcLedger` against real Postgres
- Temporal workflow tests on `TestWorkflowEnvironment`
- Additional rail adapters and an ISO 20022 normalization layer
