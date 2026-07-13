package com.prompthub.settlement.infrastructure.messaging.kafka.producer;

import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaSettlementEventPublisher implements SettlementEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long publishTimeoutMs;

    public KafkaSettlementEventPublisher(
            @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            @Value("${settlement.outbox.publish-timeout-ms:10000}") long publishTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    @Override
    public void publish(String topic, UUID aggregateId, String payload) {
        try {
            kafkaTemplate.send(topic, aggregateId.toString(), payload)
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw publishFailed(exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            throw publishFailed(exception);
        }
    }

    private SettlementException publishFailed(Exception cause) {
        return new SettlementException(SettlementErrorCode.SETTLEMENT_EVENT_PUBLISH_FAILED, cause);
    }
}
