package com.prompthub.settlement.infrastructure.persistence.outbox;

import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;
import com.prompthub.settlement.domain.model.enums.SettlementBatchStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventJpaRepository extends JpaRepository<SettlementOutboxEvent, UUID> {

    @Query("""
            select e
            from SettlementOutboxEvent e
            where e.status = :status
              and (e.lastAttemptedAt is null or e.lastAttemptedAt < :attemptedBefore)
              and exists (
                  select b.id
                  from SettlementBatch b
                  where b.id = e.settlementBatchId
                    and b.status = :batchStatus
              )
            order by e.occurredAt asc, e.eventId asc
            """)
    List<SettlementOutboxEvent> findPendingBefore(
            @Param("status") OutboxEventStatus status,
            @Param("attemptedBefore") LocalDateTime attemptedBefore,
            @Param("batchStatus") SettlementBatchStatus batchStatus,
            Pageable pageable);

    @Query("""
            select e
            from SettlementOutboxEvent e
            where e.status = :status
              and (e.lastAttemptedAt is null or e.lastAttemptedAt < :attemptedBefore)
              and exists (
                  select b.id
                  from SettlementBatch b
                  where b.id = e.settlementBatchId
                    and b.status = :batchStatus
              )
              and (e.occurredAt > :cursorOccurredAt
                   or (e.occurredAt = :cursorOccurredAt and e.eventId > :cursorEventId))
            order by e.occurredAt asc, e.eventId asc
            """)
    List<SettlementOutboxEvent> findPendingBeforeAfterCursor(
            @Param("status") OutboxEventStatus status,
            @Param("attemptedBefore") LocalDateTime attemptedBefore,
            @Param("batchStatus") SettlementBatchStatus batchStatus,
            @Param("cursorOccurredAt") LocalDateTime cursorOccurredAt,
            @Param("cursorEventId") UUID cursorEventId,
            Pageable pageable);

    @Query("""
            select e
            from SettlementOutboxEvent e
            where e.settlementBatchId = :settlementBatchId
              and e.status = :status
            order by e.occurredAt asc, e.eventId asc
            """)
    List<SettlementOutboxEvent> findPendingByBatchId(
            @Param("settlementBatchId") UUID settlementBatchId,
            @Param("status") OutboxEventStatus status,
            Pageable pageable);

    @Query("""
            select e
            from SettlementOutboxEvent e
            where e.settlementBatchId = :settlementBatchId
              and e.status = :status
              and (e.occurredAt > :cursorOccurredAt
                   or (e.occurredAt = :cursorOccurredAt and e.eventId > :cursorEventId))
            order by e.occurredAt asc, e.eventId asc
            """)
    List<SettlementOutboxEvent> findPendingByBatchIdAfterCursor(
            @Param("settlementBatchId") UUID settlementBatchId,
            @Param("status") OutboxEventStatus status,
            @Param("cursorOccurredAt") LocalDateTime cursorOccurredAt,
            @Param("cursorEventId") UUID cursorEventId,
            Pageable pageable);
}
