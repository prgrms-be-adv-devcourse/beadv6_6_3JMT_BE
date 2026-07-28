package com.prompthub.notification.infra.messaging.kafka;

import com.prompthub.common.event.EventMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEventMessageParserTest {

    private static final String ORDER_PAID_RELAY_VALUE = """
        {
          "eventId":"00000000-0000-0000-0000-000000000101",
          "eventType":"ORDER_PAID",
          "occurredAt":"2026-07-27T10:00:00",
          "aggregateType":"ORDER",
          "aggregateId":"00000000-0000-0000-0000-000000000201",
          "payload":{
            "orderId":"00000000-0000-0000-0000-000000000201",
            "buyerId":"00000000-0000-0000-0000-000000000301",
            "orderNumber":"ORD-20260727-0001",
            "totalOrderAmount":10000,
            "paidAt":"2026-07-27T10:00:00"
          }
        }
        """;

    private final NotificationEventMessageParser parser =
        new NotificationEventMessageParser(new ObjectMapper());

    @Test
    void parsesHeaderlessOutboxRelayJsonAsEventMessage() {
        EventMessage<JsonNode> message = parser.parse(ORDER_PAID_RELAY_VALUE);

        assertThat(message.eventId())
            .hasToString("00000000-0000-0000-0000-000000000101");
        assertThat(message.eventType()).isEqualTo("ORDER_PAID");
        assertThat(message.payload().path("buyerId").asText())
            .isEqualTo("00000000-0000-0000-0000-000000000301");
    }

    @Test
    void rejectsMalformedJsonAsDeserializationFailureWithoutExposingRawMessage() {
        String rawMessage = "{\"secret\":\"not-closed\"";

        assertThatThrownBy(() -> parser.parse(rawMessage))
            .isInstanceOf(NotificationEventDeserializeException.class)
            .hasMessageNotContaining(rawMessage);
    }

    @Test
    void rejectsMissingEnvelopeMetadataAsContractFailure() {
        String missingEventId = """
            {
              "eventType":"ORDER_PAID",
              "occurredAt":"2026-07-27T10:00:00",
              "aggregateType":"ORDER",
              "aggregateId":"00000000-0000-0000-0000-000000000201",
              "payload":{}
            }
            """;

        assertThatThrownBy(() -> parser.parse(missingEventId))
            .isInstanceOf(NotificationEventContractException.class);
    }
}
