package com.prompthub.order.infra.grpc.client.product;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Set;
import java.util.function.Predicate;

public final class ProductGrpcFailurePredicate implements Predicate<Throwable> {

	private static final Set<Status.Code> SYSTEM_FAILURE_CODES = Set.of(
		Status.Code.UNAVAILABLE,
		Status.Code.DEADLINE_EXCEEDED,
		Status.Code.RESOURCE_EXHAUSTED,
		Status.Code.INTERNAL,
		Status.Code.UNKNOWN
	);

	@Override
	public boolean test(Throwable throwable) {
		return throwable instanceof StatusRuntimeException exception
			&& SYSTEM_FAILURE_CODES.contains(exception.getStatus().getCode());
	}
}
