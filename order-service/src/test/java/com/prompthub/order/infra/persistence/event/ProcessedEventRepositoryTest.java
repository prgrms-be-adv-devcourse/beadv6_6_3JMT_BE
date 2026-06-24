package com.prompthub.order.infra.persistence.event;

import com.prompthub.order.infra.persistence.config.JpaConfig;
import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
class ProcessedEventRepositoryTest {

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    @DisplayName("정상 저장: 새로운 이벤트는 저장에 성공한다")
    void save_success() {
        ProcessedEvent event = ProcessedEvent.builder()
            .eventId("event-1234")
            .eventType("PAYMENT_APPROVED")
            .consumerGroup("order-service")
            .build();

        ProcessedEvent savedEvent = processedEventRepository.saveAndFlush(event);

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getEventId()).isEqualTo("event-1234");
        assertThat(savedEvent.getConsumerGroup()).isEqualTo("order-service");
        
        boolean exists = processedEventRepository.existsByEventIdAndConsumerGroup("event-1234", "order-service");
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("중복 저장 방어: 동일한 event_id와 consumer_group으로 저장 시 DataIntegrityViolationException이 발생한다")
    void save_duplicate_throwsException() {
        ProcessedEvent event1 = ProcessedEvent.builder()
            .eventId("event-duplicate")
            .eventType("PAYMENT_APPROVED")
            .consumerGroup("order-service")
            .build();

        ProcessedEvent event2 = ProcessedEvent.builder()
            .eventId("event-duplicate") // Same event ID
            .eventType("PAYMENT_APPROVED")
            .consumerGroup("order-service") // Same consumer group
            .build();

        processedEventRepository.saveAndFlush(event1);

        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(event2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
