package com.prompthub.paymentservice.infrastructure;

import com.prompthub.paymentservice.domain.RefundStatus;
import com.prompthub.paymentservice.domain.model.Refund;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
    }
)
@Testcontainers
class RefundJpaRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    RefundJpaRepository refundJpaRepository;

    @Test
    void refund_save_findById_round_trip() {
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();

        Refund refund = Refund.create(paymentId, userId, 5_000, "단순 변심", orderProductId);

        Refund saved = refundJpaRepository.saveAndFlush(refund);

        Refund found = refundJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Refund not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPaymentId()).isEqualTo(paymentId);
        assertThat(found.getOrderProductId()).isEqualTo(orderProductId);
        assertThat(found.getRefundAmount()).isEqualTo(5_000);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(found.getRequestedAt()).isNotNull();
        assertThat(found.getCompletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void refund_without_order_product_id() {
        Refund refund = Refund.create(
            UUID.randomUUID(), UUID.randomUUID(),
            10_000, "전체 환불", null
        );

        Refund saved = refundJpaRepository.saveAndFlush(refund);

        Refund found = refundJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Refund not found"));

        assertThat(found.getOrderProductId()).isNull();
    }
}
