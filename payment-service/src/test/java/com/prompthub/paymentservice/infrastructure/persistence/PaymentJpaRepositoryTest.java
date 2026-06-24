package com.prompthub.paymentservice.infrastructure.persistence;

import com.prompthub.paymentservice.domain.model.Payment;
import com.prompthub.paymentservice.domain.model.PaymentStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class PaymentJpaRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @MockitoBean
    @SuppressWarnings("rawtypes")
    KafkaTemplate kafkaTemplate;

    @Autowired
    PaymentJpaRepository paymentJpaRepository;

    @Test
    void payment_save_findById_round_trip() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Payment payment = Payment.create(
            orderId, userId,
            "pg-tx-001", "TOSS_PAYMENTS", "CARD", true,
            10_000, 1_000
        );

        Payment saved = paymentJpaRepository.saveAndFlush(payment);

        Payment found = paymentJpaRepository.findById(saved.getId())
            .orElseThrow(() -> new AssertionError("Payment not found"));

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getPgTxId()).isEqualTo("pg-tx-001");
        assertThat(found.getIdempotencyKey()).isEqualTo("pay-" + orderId);
        assertThat(found.getTotalAmount()).isEqualTo(9_000);
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(found.getApprovedAmount()).isNull();
        assertThat(found.getCanceledAmount()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }
}
