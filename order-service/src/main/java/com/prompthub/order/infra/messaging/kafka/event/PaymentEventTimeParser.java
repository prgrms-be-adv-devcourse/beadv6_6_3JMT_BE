package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;

final class PaymentEventTimeParser {

	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	private PaymentEventTimeParser() {
	}

	static LocalDateTime parseRequired(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("payment event time is required");
		}
		return parse(value);
	}

	static LocalDateTime parseOrElse(String value, LocalDateTime fallback) {
		return value == null || value.isBlank()
			? Objects.requireNonNull(fallback, "fallback payment event time is required")
			: parse(value);
	}

	private static LocalDateTime parse(String value) {
		try {
			return OffsetDateTime.parse(value)
				.withOffsetSameInstant(KST)
				.toLocalDateTime();
		} catch (DateTimeParseException ignored) {
			return LocalDateTime.parse(value);
		}
	}
}
