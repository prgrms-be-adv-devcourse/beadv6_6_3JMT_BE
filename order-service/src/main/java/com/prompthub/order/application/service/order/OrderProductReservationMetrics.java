package com.prompthub.order.application.service.order;

import java.time.Duration;

public interface OrderProductReservationMetrics {

	void recordAttempt(ReservationOutcome outcome);

	void recordRedis(
		RedisOperation operation,
		RedisOutcome outcome,
		Duration duration
	);

	enum ReservationOutcome {
		SUCCESS,
		CONFLICT,
		ERROR
	}

	enum RedisOperation {
		ACQUIRE,
		EXISTS,
		RELEASE
	}

	enum RedisOutcome {
		SUCCESS,
		ERROR
	}
}
