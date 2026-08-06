package com.prompthub.order.infra.messaging.kafka.producer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxErrorSanitizerTest {

	@Test
	void usesRootCauseClassAndRedactsMultilineSensitiveMessage() {
		Throwable error = new IllegalStateException("outer", new IllegalArgumentException(
			"broker unavailable\npassword=super-secret\tBearer another-secret"
		));

		String sanitized = OutboxErrorSanitizer.sanitize(error);

		assertThat(sanitized).isEqualTo(
			"IllegalArgumentException: broker unavailable password=[REDACTED] Bearer [REDACTED]"
		);
		assertThat(sanitized).doesNotContain("\n", "\r", "super-secret", "another-secret");
	}

	@Test
	void truncatesSanitizedErrorAtTwoThousandCharacters() {
		String sanitized = OutboxErrorSanitizer.sanitize(new IllegalArgumentException("x".repeat(2_100)));

		assertThat(sanitized).hasSize(2_000).startsWith("IllegalArgumentException: ");
	}

	@Test
	void redactsAuthorizationBearerCredentialAndRemovesStackTraceShapedLines() {
		String credential = "credential-that-must-not-leak";
		Throwable error = new IllegalArgumentException(
			"Authorization: Bearer " + credential + "\n\tat com.prompthub.order.OutboxRelay.publish(OutboxRelay.java:99)"
		);

		String sanitized = OutboxErrorSanitizer.sanitize(error);

		assertThat(sanitized)
			.startsWith("IllegalArgumentException: Authorization")
			.doesNotContain(credential, "at com.prompthub", "\n", "\r")
			.hasSizeLessThanOrEqualTo(2_000);
	}
}
