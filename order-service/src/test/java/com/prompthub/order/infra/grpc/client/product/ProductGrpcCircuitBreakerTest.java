package com.prompthub.order.infra.grpc.client.product;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.Status;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductGrpcCircuitBreakerTest {

	@Test
	void keepsCircuitBreakerClosedAfterTwentySuccessfulCalls() {
		CircuitBreaker circuitBreaker = circuitBreaker();

		for (int count = 0; count < 20; count++) {
			execute(circuitBreaker, successfulCall());
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
	}

	@Test
	void keepsCircuitBreakerClosedBeforeMinimumNumberOfCalls() {
		CircuitBreaker circuitBreaker = circuitBreaker();

		for (int count = 0; count < 9; count++) {
			assertThatThrownBy(() -> execute(circuitBreaker, failingCall())).isInstanceOf(RuntimeException.class);
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(9);
	}

	@Test
	void opensAtFailureRateThresholdAndStaysClosedBelowIt() {
		CircuitBreaker belowThreshold = circuitBreaker();
		for (int count = 0; count < 6; count++) {
			execute(belowThreshold, successfulCall());
		}
		for (int count = 0; count < 4; count++) {
			assertThatThrownBy(() -> execute(belowThreshold, failingCall())).isInstanceOf(RuntimeException.class);
		}
		assertThat(belowThreshold.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

		CircuitBreaker atThreshold = circuitBreaker();
		for (int count = 0; count < 5; count++) {
			execute(atThreshold, successfulCall());
		}
		for (int count = 0; count < 5; count++) {
			assertThatThrownBy(() -> execute(atThreshold, failingCall())).isInstanceOf(RuntimeException.class);
		}
		assertThat(atThreshold.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void closesAfterThreeSuccessfulHalfOpenCallsAndReopensOnFailure() {
		CircuitBreaker recovered = circuitBreaker();
		recovered.transitionToOpenState();
		recovered.transitionToHalfOpenState();
		for (int count = 0; count < 3; count++) {
			execute(recovered, successfulCall());
		}
		assertThat(recovered.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

		CircuitBreaker failed = circuitBreaker();
		failed.transitionToOpenState();
		failed.transitionToHalfOpenState();
		for (int count = 0; count < 3; count++) {
			assertThatThrownBy(() -> execute(failed, failingCall())).isInstanceOf(RuntimeException.class);
		}
		assertThat(failed.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void transitionsToHalfOpenAfterWaitDurationAndClosesAfterThreeSuccessfulCalls() throws InterruptedException {
		CircuitBreaker circuitBreaker = circuitBreaker();
		for (int count = 0; count < 10; count++) {
			assertThatThrownBy(() -> execute(circuitBreaker, failingCall())).isInstanceOf(RuntimeException.class);
		}
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		long deadlineNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
		while (System.nanoTime() < deadlineNanos && circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
			try {
				execute(circuitBreaker, successfulCall());
			} catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException ignored) {
				Thread.sleep(10);
			}
		}

		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
		execute(circuitBreaker, successfulCall());
		execute(circuitBreaker, successfulCall());
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void recordsSlowCallsAndOpensWhenSlowCallRateReachesThreshold() {
		CircuitBreaker circuitBreaker = circuitBreaker();
		for (int count = 0; count < 5; count++) {
			execute(circuitBreaker, successfulCall());
		}
		for (int count = 0; count < 5; count++) {
			execute(circuitBreaker, slowSuccessfulCall());
		}

		assertThat(circuitBreaker.getMetrics().getNumberOfSlowCalls()).isEqualTo(5);
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void keepsCircuitBreakerClosedWhenSlowCallRateIsBelowThreshold() {
		CircuitBreaker circuitBreaker = circuitBreaker();
		for (int count = 0; count < 6; count++) {
			execute(circuitBreaker, successfulCall());
		}
		for (int count = 0; count < 4; count++) {
			execute(circuitBreaker, slowSuccessfulCall());
		}

		assertThat(circuitBreaker.getMetrics().getNumberOfSlowCalls()).isEqualTo(4);
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	private CircuitBreaker circuitBreaker() {
		return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
			.slidingWindowSize(20)
			.minimumNumberOfCalls(10)
			.failureRateThreshold(50)
			.slowCallDurationThreshold(Duration.ofMillis(700))
			.slowCallRateThreshold(50)
			.waitDurationInOpenState(Duration.ofMillis(100))
			.permittedNumberOfCallsInHalfOpenState(3)
			.recordException(new ProductGrpcFailurePredicate())
			.build());
	}

	private Supplier<String> successfulCall() {
		return () -> "ok";
	}

	private Supplier<String> failingCall() {
		return () -> {
			throw Status.UNAVAILABLE.asRuntimeException();
		};
	}

	private Supplier<String> slowSuccessfulCall() {
		return () -> {
			try {
				Thread.sleep(750);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
			return "ok";
		};
	}

	private String execute(CircuitBreaker circuitBreaker, Supplier<String> supplier) {
		return CircuitBreaker.decorateSupplier(circuitBreaker, supplier).get();
	}
}
