# Source Line Release on Settlement Cancel

When an admin cancels a settlement, the source lines bound to it are released by loading
each line and clearing its `settlementId` through a domain method — JPA dirty checking then
writes one `UPDATE` per row. It is not a single bulk `UPDATE`.

## How

`SettlementApplicationService.cancel()` runs inside one `@Transactional`:

```java
@Override
@Transactional
public SettlementResponse cancel(UUID settlementId) {
    Settlement settlement = findSettlement(settlementId);
    settlement.cancel(LocalDateTime.now());          // status -> CANCELLED (soft)

    List<SettlementSourceLine> lines = settlementSourceRepository.findBySettlementId(settlementId);
    lines.forEach(line -> line.release(settlementId));   // clears the FK in memory

    settlementRepository.save(settlement);
    return SettlementResponse.from(settlement);
}
```

The actual unbind is a domain method on the source line:

```java
public void release(UUID settlementId) {
    if (this.settlementId != null && this.settlementId.equals(settlementId)) {
        this.settlementId = null;   // only releases lines bound to *this* settlement
    }
}
```

So the flow is:

- `findBySettlementId` returns **managed (persistent) entities** — only the lines bound to
  the canceled settlement.
- `release()` mutates a field in memory; there is **no explicit `save` per line**. Because the
  entities are managed, that is not needed.
- At commit (flush), **dirty checking emits one `UPDATE ... WHERE id = ?` per changed line**.
- Result: **1 SELECT + N UPDATEs**, where N is the number of lines on that settlement.

Released lines have `settlement_id = NULL`, which is exactly the condition the re-settlement
reader looks for (`findSettleableLines` filters `settlementId is null`), so they fall back into
the next run's candidate set. Neither the `Settlement` row nor the source-line rows are deleted.

## The trade-off

The choice is **per-row dirty checking (current)** vs. **one bulk `UPDATE`**:

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update SettlementSourceLine l set l.settlementId = null where l.settlementId = :settlementId")
int releaseAllBySettlementId(@Param("settlementId") UUID settlementId);
```

Bulk turns N UPDATEs into one statement and skips loading N entities into the persistence
context. But a bulk update goes straight to the DB and bypasses three things the current
approach gets for free:

- **Auditing (`updatedAt`).** `SettlementSourceLine extends BaseEntity`, which carries
  `@LastModifiedDate updatedAt` via `AuditingEntityListener`. A bulk update does **not** fire
  the listener, so `updated_at` is left stale. To keep it you must set it in the query by hand
  (`set l.settlementId = null, l.updatedAt = :now`) and thread a timestamp into the method.
- **Persistence-context consistency.** A bulk update doesn't touch the first-level cache. Any
  line already loaded (or read later) in the same transaction would still show the **stale**
  `settlementId`. `clearAutomatically = true` fixes that but clears the *whole* context, so the
  `settlement.cancel()` change must be flushed first (`flushAutomatically = true`) or it can be
  lost. Ordering becomes something you have to reason about.
- **Domain validation.** Today the unbind goes through `release()`, whose `equals` guard keeps
  the "only release lines bound to this settlement" rule **in the domain model** and unit-testable.
  Bulk moves that rule into a JPQL string in the infrastructure layer — same result, but the
  business rule leaks out of the model and now needs a `@DataJpaTest` to cover.

What bulk does **not** affect here: there is **no `@Version`** on the entity, so there is no
optimistic-lock path to bypass; and re-settlement behavior is identical either way, since both
end with `settlement_id = NULL`.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Per-row dirty checking (chosen) | `updatedAt` auditing for free; persistence context stays consistent; release rule lives in the domain and is unit-testable | 1 SELECT + N UPDATEs; loads N entities into memory |
| Bulk `UPDATE` | One statement; no entities loaded; lightest flush | Skips auditing (must set `updatedAt` by hand); needs `clear/flush` ordering care; release rule leaks into a JPQL string; needs integration test |

## When to switch to bulk

Today a settlement binds a modest number of source lines (the PAID/REFUND events for one seller
in one period), so N is small and the per-row cost is negligible while the auditing, consistency,
and domain-validation benefits are real. Keep the current approach until one of these is true:

- **N gets large.** A single settlement routinely binds **hundreds to thousands** of source
  lines (e.g. high-volume sellers, or longer settlement periods), making N round-trips or the
  flush cost show up in cancel latency.
- **Cancels happen in bulk.** Admins start canceling many settlements at once (mass re-settlement,
  reprocessing a bad batch), so N UPDATEs multiply across many settlements in one operation.
- **The flush/memory cost is measured, not assumed.** There is profiling evidence that loading the
  lines and per-row updates are a real bottleneck — not a guess.

When switching, the migration must:

1. Set `updatedAt` in the JPQL (`set l.settlementId = null, l.updatedAt = :now`) and pass the
   timestamp in, so auditing isn't silently dropped.
2. Use `@Modifying(clearAutomatically = true, flushAutomatically = true)` and confirm the
   `settlement.cancel()` change is flushed before the context is cleared.
3. Re-home the "only release lines bound to this settlement" rule — the JPQL `where settlementId
   = :id` enforces it, but cover it with a `@DataJpaTest` to replace the lost `release()` unit test.

## Why this fits us now

- Cancel is a low-frequency admin action over a small line set, so the N-update cost is paid
  rarely and is tiny per call.
- Keeping the unbind in `release()` means the rule is one domain method with a unit test, not a
  query string plus an integration test — cheaper to keep correct.
- `updatedAt` and first-level-cache consistency come automatically; with bulk we'd have to
  re-earn both by hand, which is easy to get subtly wrong.
- The switch above is cheap to make later and the trigger is concrete (line count / bulk cancels),
  so there's no cost to deferring it until the numbers actually demand it.
