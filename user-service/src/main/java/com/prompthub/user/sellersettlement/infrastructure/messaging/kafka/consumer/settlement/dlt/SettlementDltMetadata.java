package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement.dlt;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public record SettlementDltMetadata(
        String topic,
        int partition,
        long offset,
        String eventType,
        String payloadVersion,
        String exceptionCategory,
        String occurredAt
) {

    private static final String UNKNOWN = "UNKNOWN";
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    static final String EXCEPTION_CAUSE_FQCN_HEADER = "kafka_dlt-exception-cause-fqcn";
    static final String EXCEPTION_FQCN_HEADER = "kafka_dlt-exception-fqcn";
    static final String ORIGINAL_OFFSET_HEADER = "kafka_dlt-original-offset";
    static final String ORIGINAL_PARTITION_HEADER = "kafka_dlt-original-partition";
    static final String ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic";

    public static SettlementDltMetadata from(ConsumerRecord<String, String> record) {
        JsonMetadata jsonMetadata = readJsonMetadata(record.value());
        return new SettlementDltMetadata(
                stringHeader(record, ORIGINAL_TOPIC_HEADER, record.topic()),
                intHeader(record, ORIGINAL_PARTITION_HEADER, record.partition()),
                longHeader(record, ORIGINAL_OFFSET_HEADER, record.offset()),
                jsonMetadata.eventType(),
                jsonMetadata.payloadVersion(),
                exceptionCategory(record),
                occurredAt(record, jsonMetadata.occurredAt()));
    }

    public String toSlackText() {
        return """
                정산 이벤트 DLT
                topic: %s
                partition: %d
                offset: %d
                eventType: %s
                payloadVersion: %s
                exceptionCategory: %s
                occurredAt: %s
                """.formatted(
                topic, partition, offset, eventType, payloadVersion,
                exceptionCategory, occurredAt).stripTrailing();
    }

    private static JsonMetadata readJsonMetadata(String value) {
        if (value == null) {
            return JsonMetadata.unknown();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value);
            String eventType = textOrUnknown(root.path("eventType"));
            JsonNode versionNode = root.path("payload").path("payloadVersion");
            String payloadVersion;
            if (versionNode.isMissingNode() || versionNode.isNull()) {
                payloadVersion = "1";
            } else if (versionNode.isIntegralNumber()) {
                payloadVersion = versionNode.toString();
            } else {
                payloadVersion = UNKNOWN;
            }
            return new JsonMetadata(
                    eventType, payloadVersion, textOrUnknown(root.path("occurredAt")));
        } catch (JacksonException exception) {
            return JsonMetadata.unknown();
        }
    }

    private static String occurredAt(ConsumerRecord<String, String> record, String jsonOccurredAt) {
        if (!jsonOccurredAt.equals(UNKNOWN)) {
            return jsonOccurredAt;
        }
        return record.timestamp() >= 0 ? Instant.ofEpochMilli(record.timestamp()).toString() : UNKNOWN;
    }

    private static String exceptionCategory(ConsumerRecord<String, String> record) {
        String className = stringHeader(
                record, EXCEPTION_CAUSE_FQCN_HEADER,
                stringHeader(record, EXCEPTION_FQCN_HEADER, UNKNOWN));
        int separator = className.lastIndexOf('.');
        return separator >= 0 ? className.substring(separator + 1) : className;
    }

    private static String textOrUnknown(JsonNode node) {
        String value = node.isString() ? node.stringValue() : null;
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private static String stringHeader(
            ConsumerRecord<String, String> record, String name, String fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null
                ? fallback
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int intHeader(
            ConsumerRecord<String, String> record, String name, int fallback) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value().length != Integer.BYTES) {
            return fallback;
        }
        return ByteBuffer.wrap(header.value()).getInt();
    }

    private static long longHeader(
            ConsumerRecord<String, String> record, String name, long fallback) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value().length != Long.BYTES) {
            return fallback;
        }
        return ByteBuffer.wrap(header.value()).getLong();
    }

    private record JsonMetadata(String eventType, String payloadVersion, String occurredAt) {

        private static JsonMetadata unknown() {
            return new JsonMetadata(UNKNOWN, UNKNOWN, UNKNOWN);
        }
    }
}
