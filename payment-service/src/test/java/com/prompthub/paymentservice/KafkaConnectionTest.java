package com.prompthub.paymentservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@EmbeddedKafka(partitions = 1)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
class KafkaConnectionTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void Kafka_브로커_연결_확인() {
        assertThatNoException().isThrownBy(
                () -> kafkaTemplate.send("test-topic", "ping")
        );
    }
}
