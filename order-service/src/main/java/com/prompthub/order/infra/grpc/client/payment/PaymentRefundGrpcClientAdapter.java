package com.prompthub.order.infra.grpc.client.payment;

import com.prompthub.grpc.payment.refund.v1.GetRefundStatusRequest;
import com.prompthub.grpc.payment.refund.v1.GetRefundStatusResponse;
import com.prompthub.grpc.payment.refund.v1.PaymentRefundQueryServiceGrpc;
import com.prompthub.order.application.client.PaymentRefundStatusClient;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.exception.PaymentRefundStatusQueryException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Profile({"dev", "prod"})
public class PaymentRefundGrpcClientAdapter implements PaymentRefundStatusClient {

	private final PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub stub;
	private final PaymentRefundGrpcResilience resilience;
	private final int deadlineMs;

	public PaymentRefundGrpcClientAdapter(
		PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub stub,
		PaymentRefundGrpcResilience resilience,
		@Value("${prompthub.grpc.payment-refund.deadline-ms:2000}") int deadlineMs
	) {
		this.stub = stub;
		this.resilience = resilience;
		this.deadlineMs = deadlineMs;
	}

	@Override
	public PaymentRefundStatusResult getRefundStatus(UUID refundRequestId) {
		try {
			return execute(() -> map(stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
				.getRefundStatus(GetRefundStatusRequest.newBuilder()
						.setRefundRequestId(refundRequestId.toString())
						.build())));
		} catch (StatusRuntimeException exception) {
			if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
				return PaymentRefundStatusResult.notFound();
			}
			throw queryFailure(refundRequestId, exception);
		} catch (CallNotPermittedException | BulkheadFullException | DateTimeParseException exception) {
			throw queryFailure(refundRequestId, exception);
		} catch (IllegalArgumentException exception) {
			throw queryFailure(refundRequestId, exception);
		}
	}

	private <T> T execute(Supplier<T> supplier) {
		CircuitBreaker circuitBreaker = resilience.circuitBreaker();
		Bulkhead bulkhead = resilience.bulkhead();
		return CircuitBreaker.decorateSupplier(
			circuitBreaker,
			Bulkhead.decorateSupplier(bulkhead, supplier)
		).get();
	}

	private PaymentRefundStatusResult map(GetRefundStatusResponse response) {
		return switch (response.getStatus()) {
			case REFUND_STATUS_PROCESSING -> PaymentRefundStatusResult.processing();
			case REFUND_STATUS_COMPLETED -> PaymentRefundStatusResult.completed(parseDateTime(response.getRefundedAt()));
			case REFUND_STATUS_FAILED -> PaymentRefundStatusResult.failed(
				response.getFailureCode(),
				response.getFailureReason()
			);
			case REFUND_STATUS_NOT_FOUND, REFUND_STATUS_UNSPECIFIED, UNRECOGNIZED -> PaymentRefundStatusResult.notFound();
		};
	}

	private LocalDateTime parseDateTime(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Completed refund response is missing refundedAt");
		}
		try {
			return LocalDateTime.parse(value);
		} catch (DateTimeParseException ignored) {
			return OffsetDateTime.parse(value).toLocalDateTime();
		}
	}

	private PaymentRefundStatusQueryException queryFailure(UUID refundRequestId, Throwable cause) {
		return new PaymentRefundStatusQueryException(
			"Payment refund status query failed. refundRequestId=" + refundRequestId,
			cause
		);
	}
}
