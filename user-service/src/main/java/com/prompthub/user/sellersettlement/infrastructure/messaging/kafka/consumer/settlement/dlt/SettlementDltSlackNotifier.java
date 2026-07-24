package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement.dlt;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@ConditionalOnExpression(
        "T(org.springframework.util.StringUtils).hasText('${user.kafka.dlt.slack.webhook-url:}')")
public class SettlementDltSlackNotifier {

    private static final String LISTENER_GROUP = "user-service-settlement-dlt-notifier";

    private final RestClient restClient;
    private final SettlementDltSlackProperties properties;

    public SettlementDltSlackNotifier(
            RestClient.Builder restClientBuilder,
            SettlementDltSlackProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${user.kafka.consumer.settlement.topic}.DLT",
            groupId = LISTENER_GROUP,
            containerFactory = "settlementDltKafkaListenerContainerFactory"
    )
    public void notify(ConsumerRecord<String, String> record) {
        SettlementDltMetadata metadata = SettlementDltMetadata.from(record);
        try {
            restClient.post()
                    .uri(properties.webhookUrl())
                    .body(new SlackMessage(metadata.toSlackText()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.error(
                    "정산 DLT Slack 알림 전송에 실패했습니다. topic={}, partition={}, offset={}, exceptionCategory={}",
                    metadata.topic(), metadata.partition(), metadata.offset(), metadata.exceptionCategory());
        }
    }

    private record SlackMessage(String text) {
    }
}
