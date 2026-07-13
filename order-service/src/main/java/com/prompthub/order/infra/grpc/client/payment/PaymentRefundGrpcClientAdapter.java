package com.prompthub.order.infra.grpc.client.payment;

import com.prompthub.grpc.payment.refund.v1.GetRefundStatusRequest;
import com.prompthub.grpc.payment.refund.v1.GetRefundStatusResponse;
import com.prompthub.grpc.payment.refund.v1.PaymentRefundQueryServiceGrpc;
import com.prompthub.order.application.client.PaymentRefundStatusClient;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Profile({"dev", "prod"})
public class PaymentRefundGrpcClientAdapter implements PaymentRefundStatusClient {

	private final PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub stub;
	private final int deadlineMs;

	public PaymentRefundGrpcClientAdapter(
		PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub stub,
		@Value("${prompthub.grpc.payment-refund.deadline-ms:2000}") int deadlineMs
	) {
		this.stub = stub;
		this.deadlineMs = deadlineMs;
	}

	@Override
	public PaymentRefundStatusResult getRefundStatus(UUID refundRequestId) {
		try {
			GetRefundStatusResponse response = stub
				.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
				.getRefundStatus(GetRefundStatusRequest.newBuilder()
					.setRefundRequestId(refundRequestId.toString())
					.build());
			return map(response);
		} catch (StatusRuntimeException exception) {
			if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
				return PaymentRefundStatusResult.notFound();
			}
			throw exception;
		}
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
		return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
	}
}
