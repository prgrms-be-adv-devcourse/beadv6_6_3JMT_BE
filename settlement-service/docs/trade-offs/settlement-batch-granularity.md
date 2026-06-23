# Settlement Batch Granularity

The settlement job is a three-step pipeline that aggregates per seller, not a single
chunk step that streams orders one by one.

## How

- The job runs three steps in order:
  - `createSettlementBatchStep` (tasklet) — opens a `SettlementBatch` record for the period.
  - `settlementStep` (chunk) — the actual settlement work.
  - `completeSettlementBatchStep` (tasklet) — marks the batch done.
- The chunk step's item is one seller, not one order: `<SettlementTarget, Settlement>`.
  - Reader hands out settleable seller ids for the period, one at a time.
  - Processor calls `CalculateSettlementUseCase` and returns one `Settlement` (with its
    `SettlementDetail` lines) per seller. Sellers with no products are dropped (`null`).
  - Writer saves the `Settlement` aggregates.
- A job-level listener fails the `SettlementBatch` if the run breaks.

```java
new JobBuilder(SETTLEMENT_JOB_NAME, jobRepository)
        .listener(settlementBatchFailureListener)
        .start(createSettlementBatchStep)
        .next(settlementStep)
        .next(completeSettlementBatchStep)
        .build();
```

The earlier version was one chunk step over `Order` (`<Order, SettlementItem>`) using a
`JpaPagingItemReader`. No batch record, no surrounding steps — read orders, map, write.

## The trade-off

The item unit decides almost everything else.

Per order is the natural fit for chunk batching: the paging reader streams rows, state is
saved per page, and a restart picks up where it left off. But an order is not a unit anyone
settles. Settlement is "this seller, this month," so the per-order job would still have to
group orders back together somewhere, and the result it writes doesn't match the thing the
domain cares about.

Per seller matches the domain: one item in, one `Settlement` out. The cost is the reader.
We load all settleable seller ids up front and iterate them in memory instead of paging, so
the read isn't restartable mid-step and a very large seller set sits in memory at once. For a
monthly run over our seller count that's a fair price; if sellers grow a lot, the reader is
the first thing to revisit (page the ids, or chunk by a seller-id range).

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Per-seller, 3-step (chosen) | Item matches the domain unit; batch lifecycle is recorded and failable; calculation lives in the use case | Reader loads all seller ids in memory; read not restartable mid-step |
| Per-order, single chunk step | Paging reader streams rows; restartable; lowest memory | Order isn't a settlement unit — needs regrouping; no batch record; mapping logic leaks into infra |
| Per-seller, but page the ids | Domain unit + bounded memory | More reader code; paging a derived id list is fiddly |

## Why this fits us

- Settlement is reported and paid per seller per period. Making that the batch item means the
  job produces exactly the records we keep, with no regrouping step bolted on.
- The `SettlementBatch` record gives us one row per run to track status and failures, which the
  single-step version had nowhere to put.
- Keeping the math in `CalculateSettlementUseCase` keeps the batch as flow control only, so the
  same rules can run outside the batch later without copying logic.
- The memory cost is bounded by seller count for a monthly run, which is small enough today. We
  noted the paging fallback above for when it isn't.
