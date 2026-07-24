package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void DLT_발행_성공을_확인한_뒤에만_복구_offset을_커밋한다() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        DefaultErrorHandler handler = new KafkaConfig("localhost:9092", "user-service", "earliest", false)
                .settlementKafkaErrorHandler(kafkaTemplate);

        Object failureTracker = ReflectionTestUtils.getField(handler, "failureTracker");
        DeadLetterPublishingRecoverer recoverer = (DeadLetterPublishingRecoverer) ReflectionTestUtils.getField(
                failureTracker,
                "recoverer"
        );

        assertThat(ReflectionTestUtils.getField(recoverer, "failIfSendResultIsError")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(handler, "commitRecovered")).isEqualTo(true);
    }
}
