package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.OrderSnapshot;
import com.prompthub.paymentservice.domain.model.OrderSnapshotSource;
import com.prompthub.paymentservice.support.AbstractJpaTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSnapshotJpaRepositoryTest extends AbstractJpaTest {

    @Autowired
    OrderSnapshotJpaRepository orderSnapshotJpaRepository;

    @Test
    void order_snapshot_save_findByOrderId_round_trip() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        OffsetDateTime orderCreatedAt = OffsetDateTime.now(ZoneOffset.ofHours(9));

        OrderSnapshot snapshot = OrderSnapshot.create(
            orderId, buyerId, 50_000, OrderSnapshotSource.EVENT, orderCreatedAt
        );

        OrderSnapshot saved = orderSnapshotJpaRepository.saveAndFlush(snapshot);

        OrderSnapshot found = orderSnapshotJpaRepository.findByOrderId(orderId)
            .orElseThrow(() -> new AssertionError("OrderSnapshot not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getBuyerId()).isEqualTo(buyerId);
        assertThat(found.getTotalAmount()).isEqualTo(50_000);
        assertThat(found.getSource()).isEqualTo(OrderSnapshotSource.EVENT);
        assertThat(found.getOrderCreatedAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }
}
