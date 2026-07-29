package com.prompthub.admin.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import(SettlementDeliveryQueryRepository.class)
@ActiveProfiles("test")
class SettlementDeliveryQueryRepositoryTest {

    private static final UUID FAILED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000663");
    private static final UUID SETTLEMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000635");
    private static final UUID DELIVERY_REQUEST_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000642");

    @Autowired
    private SettlementDeliveryQueryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 문제건과_식별자를_필터링하고_최근시도순으로_조회한다() {
        insert(
                FAILED_ID,
                SETTLEMENT_ID,
                DELIVERY_REQUEST_ID,
                SettlementDeliveryStatus.DELIVERY_FAILED,
                LocalDateTime.of(2026, 7, 29, 11, 0));
        insert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SettlementDeliveryStatus.MISMATCH,
                LocalDateTime.of(2026, 7, 29, 10, 0));
        insert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SettlementDeliveryStatus.RECONCILED,
                LocalDateTime.of(2026, 7, 29, 12, 0));

        var page = repository.findPage(new SettlementDeliveryListQuery(
                null, true, SETTLEMENT_ID, 0, 20));

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(delivery -> {
            assertThat(delivery.getSettlementDeliveryId()).isEqualTo(FAILED_ID);
            assertThat(delivery.getDeliveryRequestId()).isEqualTo(DELIVERY_REQUEST_ID);
        });
    }

    @Test
    void 상태별_건수에_없는_상태도_0으로_채운다() {
        insert(
                FAILED_ID,
                SETTLEMENT_ID,
                DELIVERY_REQUEST_ID,
                SettlementDeliveryStatus.DELIVERY_FAILED,
                LocalDateTime.of(2026, 7, 29, 11, 0));

        var counts = repository.countByStatus();

        assertThat(counts.get(SettlementDeliveryStatus.DELIVERY_FAILED)).isEqualTo(1);
        assertThat(counts.get(SettlementDeliveryStatus.CALCULATED)).isZero();
        assertThat(counts.get(SettlementDeliveryStatus.RECONCILED)).isZero();
        assertThat(counts.get(SettlementDeliveryStatus.MISMATCH)).isZero();
    }

    private void insert(
            UUID id,
            UUID settlementId,
            UUID deliveryRequestId,
            SettlementDeliveryStatus status,
            LocalDateTime lastAttemptAt) {
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO settlement_delivery (
                    settlement_delivery_id, delivery_request_id, settlement_id,
                    settlement_batch_id, status, attempt_count, status_reason,
                    first_attempt_at, last_attempt_at, created_at, updated_at)
                VALUES (
                    :id, :requestId, :settlementId, :batchId, :status, 3, :reason,
                    :firstAttemptAt, :lastAttemptAt, :createdAt, :updatedAt)
                """)
                .setParameter("id", id)
                .setParameter("requestId", deliveryRequestId)
                .setParameter("settlementId", settlementId)
                .setParameter("batchId", UUID.randomUUID())
                .setParameter("status", status.name())
                .setParameter("reason", "test")
                .setParameter("firstAttemptAt", lastAttemptAt.minusSeconds(4))
                .setParameter("lastAttemptAt", lastAttemptAt)
                .setParameter("createdAt", lastAttemptAt.minusMinutes(1))
                .setParameter("updatedAt", lastAttemptAt)
                .executeUpdate();
    }
}
