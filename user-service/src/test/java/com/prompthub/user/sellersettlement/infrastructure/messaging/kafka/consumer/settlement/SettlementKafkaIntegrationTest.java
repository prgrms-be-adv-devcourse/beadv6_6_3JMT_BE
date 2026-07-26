package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV2;
import com.prompthub.user.sellersettlement.application.service.SellerSettlementApplicationService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "user.kafka.listener.settlement.enabled=true",
        "eureka.client.enabled=false",
        "grpc.server.enabled=false"
})
@EmbeddedKafka(partitions = 1, topics = {"settlement-events", "settlement-events.DLT"})
@ActiveProfiles("test")
class SettlementKafkaIntegrationTest {

    private static final String TOPIC = "settlement-events";
    private static final String DLT_TOPIC = "settlement-events.DLT";
    private static final String GROUP_ID = "user-service";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private SellerSettlementApplicationService sellerSettlementApplicationService;

    @BeforeEach
    void resetMock() {
        reset(sellerSettlementApplicationService);
    }

    @Test
    @DisplayName("실패한 V2 이벤트는 3회 재시도 뒤 DLT 발행 성공 후 offset을 커밋한다")
    void retriesThenPublishesDltBeforeCommittingRecoveredOffset() throws Exception {
        String key = UUID.randomUUID().toString();
        String message = validV2Event();
        willThrow(new IllegalStateException("seed failed"))
                .given(sellerSettlementApplicationService)
                .seed(any(SettlementCreatedEventV2.class));

        try (Consumer<String, String> dltConsumer = stringConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, true, DLT_TOPIC);
            rawStringKafkaTemplate().send(TOPIC, key, message).get(5, TimeUnit.SECONDS);

            ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
                    dltConsumer,
                    DLT_TOPIC,
                    Duration.ofSeconds(15)
            );

            verify(sellerSettlementApplicationService, times(4)).seed(any(SettlementCreatedEventV2.class));
            assertThat(dltRecord.key()).isEqualTo(key);
            assertThat(dltRecord.value()).isEqualTo(message);
            assertThat(headerValue(dltRecord, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
            assertThat(waitForCommittedOffset()).isEqualTo(1L);
        }
    }

    private KafkaTemplate<String, String> rawStringKafkaTemplate() {
        Map<String, Object> properties = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }

    private Consumer<String, String> stringConsumer() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(
                properties,
                new StringDeserializer(),
                new StringDeserializer()
        ).createConsumer();
    }

    private long waitForCommittedOffset() throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                embeddedKafkaBroker.getBrokersAsString()))) {
            while (Instant.now().isBefore(deadline)) {
                Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                        .partitionsToOffsetAndMetadata()
                        .get(1, TimeUnit.SECONDS);
                OffsetAndMetadata offset = offsets.get(new TopicPartition(TOPIC, 0));
                if (offset != null) {
                    return offset.offset();
                }
                Thread.sleep(100);
            }
        }
        return -1L;
    }

    private String headerValue(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private String validV2Event() {
        UUID settlementId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        UUID orderProductId = UUID.randomUUID();
        return """
                {
                  "eventId": "%s",
                  "eventType": "SETTLEMENT_CREATED",
                  "occurredAt": "2026-07-20T01:12:03",
                  "aggregateType": "SETTLEMENT",
                  "aggregateId": "%s",
                  "payload": {
                    "payloadVersion": 2,
                    "settlementId": "%s",
                    "sellerId": "%s",
                    "periodStart": "2026-07-13",
                    "periodEnd": "2026-07-19",
                    "productCount": 1,
                    "totalAmount": 100.00,
                    "settlementTotalAmount": 85.00,
                    "feeTotalAmount": 15.00,
                    "refundAmount": 0.00,
                    "calculatedAt": "2026-07-20T01:12:02",
                    "details": [{
                      "settlementDetailId": "%s",
                      "orderProductId": "%s",
                      "lineType": "SALE",
                      "lineAmount": 100.00,
                      "feeRate": 0.1500,
                      "feeAmount": 15.00,
                      "lineSettlementAmount": 85.00,
                      "occurredAt": "2026-07-14T13:10:00"
                    }]
                  }
                }
                """.formatted(UUID.randomUUID(), settlementId, settlementId, sellerId, detailId, orderProductId);
    }
}
