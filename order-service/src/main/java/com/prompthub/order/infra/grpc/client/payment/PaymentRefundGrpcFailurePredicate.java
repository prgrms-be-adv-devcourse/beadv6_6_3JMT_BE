package com.prompthub.order.infra.grpc.client.payment;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.function.Predicate;

public final class PaymentRefundGrpcFailurePredicate implements Predicate<Throwable> {

	@Override
	public boolean test(Throwable throwable) {
		return throwable instanceof StatusRuntimeException exception
			&& exception.getStatus().getCode() != Status.Code.NOT_FOUND;
	}
}
