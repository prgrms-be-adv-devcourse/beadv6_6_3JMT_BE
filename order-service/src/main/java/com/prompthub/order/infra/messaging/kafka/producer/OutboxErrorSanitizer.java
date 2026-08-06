package com.prompthub.order.infra.messaging.kafka.producer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

public final class OutboxErrorSanitizer {

	private static final int MAX_LENGTH = 2_000;
	private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
		"(?i)(password|secret|token|authorization)\\s*[=:]\\s*[^\\s,;]+"
	);
	private static final Pattern AUTHORIZATION_BEARER = Pattern.compile(
		"(?i)authorization\\s*[=:]\\s*bearer\\s+[^\\s,;]+"
	);
	private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer\\s+[^\\s,;]+");
	private static final Pattern STACK_TRACE_LINE = Pattern.compile("(?m)^\\s*at\\s+[^\\r\\n]+(?:\\R|$)");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private OutboxErrorSanitizer() {
	}

	public static String sanitize(Throwable throwable) {
		Throwable rootCause = rootCauseOf(throwable);
		String type = rootCause.getClass().getSimpleName();
		String message = rootCause.getMessage();
		String sanitizedMessage = message == null ? "" : sanitizeMessage(message);
		String sanitized = sanitizedMessage.isEmpty() ? type : type + ": " + sanitizedMessage;
		return sanitized.length() <= MAX_LENGTH ? sanitized : sanitized.substring(0, MAX_LENGTH);
	}

	private static Throwable rootCauseOf(Throwable throwable) {
		if (throwable == null) {
			return new IllegalArgumentException("unknown outbox publish failure");
		}

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = throwable;
		while (current.getCause() != null && visited.add(current.getCause())) {
			current = current.getCause();
		}
		return current;
	}

	private static String sanitizeMessage(String message) {
		String withoutStackTrace = STACK_TRACE_LINE.matcher(message).replaceAll("");
		String oneLine = WHITESPACE.matcher(withoutStackTrace).replaceAll(" ").strip();
		String withoutAuthorizationBearer = AUTHORIZATION_BEARER.matcher(oneLine)
			.replaceAll("Authorization=[REDACTED]");
		String withoutBearer = BEARER_TOKEN.matcher(withoutAuthorizationBearer).replaceAll("Bearer [REDACTED]");
		return SENSITIVE_ASSIGNMENT.matcher(withoutBearer).replaceAll("$1=[REDACTED]");
	}
}
