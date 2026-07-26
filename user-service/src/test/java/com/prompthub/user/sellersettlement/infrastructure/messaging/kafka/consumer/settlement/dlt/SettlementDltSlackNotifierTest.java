package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement.dlt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SettlementDltSlackNotifierTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.test/services/test";
    private static final String SELLER_ID = "7c0ae024-86fa-4497-8be1-1440ce8f65de";
    private static final String DETAIL_ID = "bba2a583-599e-41a9-88bf-4521c4b5302f";

    private MockRestServiceServer server;
    private SettlementDltSlackNotifier notifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        notifier = new SettlementDltSlackNotifier(
                builder, new SettlementDltSlackProperties(WEBHOOK_URL));
    }

    @Test
    void Slack_요청은_원문을_제외하고_DLT_metadata만_담는다() {
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("settlement-events")))
                .andExpect(content().string(containsString("SETTLEMENT_CREATED")))
                .andExpect(content().string(containsString("payloadVersion: 2")))
                .andExpect(content().string(containsString("SettlementEventDeserializeException")))
                .andExpect(content().string(not(containsString(SELLER_ID))))
                .andExpect(content().string(not(containsString(DETAIL_ID))))
                .andExpect(content().string(not(containsString("100.00"))))
                .andExpect(content().string(not(containsString("details"))))
                .andRespond(withSuccess());

        notifier.notify(record());

        server.verify();
    }

    @Test
    void Slack_오류는_DLT_listener_밖으로_전파하지_않는다() {
        server.expect(once(), requestTo(WEBHOOK_URL))
                .andRespond(withServerError());

        assertThatCode(() -> notifier.notify(record())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void webhook_URL이_blank면_notifier_bean을_생성하지_않는다() {
        new ApplicationContextRunner()
                .withUserConfiguration(SettlementDltSlackNotifier.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(SettlementDltSlackNotifier.class));
    }

    private ConsumerRecord<String, String> record() {
        String payload = """
                {
                  "eventType": "SETTLEMENT_CREATED",
                  "occurredAt": "2026-07-20T01:12:03",
                  "payload": {
                    "payloadVersion": 2,
                    "sellerId": "%s",
                    "totalAmount": 100.00,
                    "details": [{"settlementDetailId": "%s"}]
                  }
                }
                """.formatted(SELLER_ID, DETAIL_ID);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "settlement-events.DLT", 0, 0L, "key", payload);
        record.headers().add(
                SettlementDltMetadata.ORIGINAL_TOPIC_HEADER,
                "settlement-events".getBytes(StandardCharsets.UTF_8));
        record.headers().add(
                SettlementDltMetadata.ORIGINAL_PARTITION_HEADER,
                ByteBuffer.allocate(Integer.BYTES).putInt(0).array());
        record.headers().add(
                SettlementDltMetadata.ORIGINAL_OFFSET_HEADER,
                ByteBuffer.allocate(Long.BYTES).putLong(15L).array());
        record.headers().add(
                SettlementDltMetadata.EXCEPTION_CAUSE_FQCN_HEADER,
                "com.prompthub.user.global.exception.SettlementEventDeserializeException"
                        .getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
