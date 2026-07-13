package com.prompthub.order.infra.grpc.client.product;

import io.grpc.Status;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductGrpcFailurePredicateTest {

	private final ProductGrpcFailurePredicate predicate = new ProductGrpcFailurePredicate();

	@Test
	void recordsOnlySystemAndNetworkGrpcFailures() {
		assertThat(predicate.test(Status.UNAVAILABLE.asRuntimeException())).isTrue();
		assertThat(predicate.test(Status.DEADLINE_EXCEEDED.asRuntimeException())).isTrue();
		assertThat(predicate.test(Status.RESOURCE_EXHAUSTED.asRuntimeException())).isTrue();
		assertThat(predicate.test(Status.INTERNAL.asRuntimeException())).isTrue();
		assertThat(predicate.test(Status.UNKNOWN.asRuntimeException())).isTrue();
	}

	@Test
	void excludesBusinessAndRequestGrpcFailures() {
		assertThat(predicate.test(Status.NOT_FOUND.asRuntimeException())).isFalse();
		assertThat(predicate.test(Status.INVALID_ARGUMENT.asRuntimeException())).isFalse();
		assertThat(predicate.test(Status.ALREADY_EXISTS.asRuntimeException())).isFalse();
		assertThat(predicate.test(Status.UNAUTHENTICATED.asRuntimeException())).isFalse();
		assertThat(predicate.test(Status.PERMISSION_DENIED.asRuntimeException())).isFalse();
		assertThat(predicate.test(BulkheadFullException.createBulkheadFullException(
			Bulkhead.ofDefaults("productGrpcBulkhead")
		))).isFalse();
	}
}
