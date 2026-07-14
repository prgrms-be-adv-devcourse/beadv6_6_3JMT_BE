package com.prompthub.order.infra.grpc.client.payment;

import com.prompthub.grpc.payment.refund.v1.GetRefundStatusRequest;
import com.prompthub.grpc.payment.refund.v1.GetRefundStatusResponse;
import com.prompthub.grpc.payment.refund.v1.PaymentRefundQueryServiceGrpc;
import com.prompthub.grpc.payment.refund.v1.RefundStatus;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import com.prompthub.order.application.exception.PaymentRefundStatusQueryException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRefundGrpcClientAdapterTest {

	private Server server;
	private ManagedChannel channel;

	@AfterEach
	void tearDown() {
		if (channel != null) channel.shutdownNow();
		if (server != null) server.shutdownNow();
	}

	@Test
	@DisplayName("완료 응답을 환불 완료 조회 결과로 변환한다")
	void getRefundStatus_completed_mapsResult() throws IOException {
		UUID refundRequestId = UUID.randomUUID();
		LocalDateTime refundedAt = LocalDateTime.of(2026, 7, 11, 12, 5);
		PaymentRefundGrpcClientAdapter adapter = adapterWith(new PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase() {
			@Override
			public void getRefundStatus(
				GetRefundStatusRequest request,
				StreamObserver<GetRefundStatusResponse> observer
			) {
				assertThat(request.getRefundRequestId()).isEqualTo(refundRequestId.toString());
				observer.onNext(GetRefundStatusResponse.newBuilder()
					.setStatus(RefundStatus.REFUND_STATUS_COMPLETED)
					.setRefundedAt(refundedAt.toString())
					.build());
				observer.onCompleted();
			}
		});

		PaymentRefundStatusResult result = adapter.getRefundStatus(refundRequestId);

		assertThat(result.status()).isEqualTo(PaymentRefundStatusResult.Status.COMPLETED);
		assertThat(result.refundedAt()).isEqualTo(refundedAt);
	}

	@Test
	@DisplayName("오프셋이 포함된 완료 시각을 로컬 완료 시각으로 변환한다")
	void getRefundStatus_completedWithOffset_mapsResult() throws IOException {
		OffsetDateTime refundedAt = OffsetDateTime.of(2026, 7, 11, 12, 5, 0, 0, ZoneOffset.ofHours(9));
		PaymentRefundGrpcClientAdapter adapter = adapterReturning(GetRefundStatusResponse.newBuilder()
			.setStatus(RefundStatus.REFUND_STATUS_COMPLETED)
			.setRefundedAt(refundedAt.toString())
			.build());

		PaymentRefundStatusResult result = adapter.getRefundStatus(UUID.randomUUID());

		assertThat(result.refundedAt()).isEqualTo(refundedAt.toLocalDateTime());
	}

	@Test
	@DisplayName("처리 중 응답을 처리 중 조회 결과로 변환한다")
	void getRefundStatus_processing_mapsResult() throws IOException {
		PaymentRefundGrpcClientAdapter adapter = adapterReturning(GetRefundStatusResponse.newBuilder()
			.setStatus(RefundStatus.REFUND_STATUS_PROCESSING)
			.build());

		assertThat(adapter.getRefundStatus(UUID.randomUUID()).status())
			.isEqualTo(PaymentRefundStatusResult.Status.PROCESSING);
	}

	@Test
	@DisplayName("실패 응답의 코드와 사유를 보존한다")
	void getRefundStatus_failed_mapsResult() throws IOException {
		PaymentRefundGrpcClientAdapter adapter = adapterReturning(GetRefundStatusResponse.newBuilder()
			.setStatus(RefundStatus.REFUND_STATUS_FAILED)
			.setFailureCode("TOSS_REJECTED")
			.setFailureReason("환불 승인이 거절되었습니다")
			.build());

		PaymentRefundStatusResult result = adapter.getRefundStatus(UUID.randomUUID());

		assertThat(result.status()).isEqualTo(PaymentRefundStatusResult.Status.FAILED);
		assertThat(result.failureCode()).isEqualTo("TOSS_REJECTED");
		assertThat(result.failureReason()).isEqualTo("환불 승인이 거절되었습니다");
	}

	@Test
	@DisplayName("미발견과 알 수 없는 상태는 미발견 조회 결과로 보수적으로 변환한다")
	void getRefundStatus_unknownStatuses_mapNotFound() throws IOException {
		for (int statusValue : new int[] {
			RefundStatus.REFUND_STATUS_NOT_FOUND_VALUE,
			RefundStatus.REFUND_STATUS_UNSPECIFIED_VALUE,
			999
		}) {
			PaymentRefundGrpcClientAdapter adapter = adapterReturning(GetRefundStatusResponse.newBuilder()
				.setStatusValue(statusValue)
				.build());

			assertThat(adapter.getRefundStatus(UUID.randomUUID()).status())
				.isEqualTo(PaymentRefundStatusResult.Status.NOT_FOUND);
			tearDown();
		}
	}

	@Test
	@DisplayName("gRPC NOT_FOUND는 미발견 조회 결과로 변환한다")
	void getRefundStatus_notFound_mapsResult() throws IOException {
		PaymentRefundGrpcClientAdapter adapter = adapterWith(new PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase() {
			@Override
			public void getRefundStatus(GetRefundStatusRequest request, StreamObserver<GetRefundStatusResponse> observer) {
				observer.onError(Status.NOT_FOUND.asRuntimeException());
			}
		});

		PaymentRefundStatusResult result = adapter.getRefundStatus(UUID.randomUUID());

		assertThat(result.status()).isEqualTo(PaymentRefundStatusResult.Status.NOT_FOUND);
	}

	@Test
	@DisplayName("gRPC 전송 실패는 원인을 보존한 애플리케이션 예외로 변환한다")
	void getRefundStatus_transportFailures_wrapCause() throws IOException {
		for (Status status : new Status[] {Status.UNAVAILABLE, Status.RESOURCE_EXHAUSTED, Status.INTERNAL}) {
			StatusRuntimeException transportFailure = status.asRuntimeException();
			PaymentRefundGrpcClientAdapter adapter = adapterFailingWith(transportFailure);

			assertThatThrownBy(() -> adapter.getRefundStatus(UUID.randomUUID()))
				.isInstanceOf(PaymentRefundStatusQueryException.class)
				.hasCause(transportFailure);
			tearDown();
		}
	}

	@Test
	@DisplayName("기한을 넘긴 gRPC 호출은 원인을 보존한 애플리케이션 예외로 변환한다")
	void getRefundStatus_deadlineExceeded_wrapsCause() throws IOException {
		PaymentRefundGrpcClientAdapter adapter = adapterWith(
			new PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase() {
				@Override
				public void getRefundStatus(
					GetRefundStatusRequest request,
					StreamObserver<GetRefundStatusResponse> observer
				) {
					// The channel deadline terminates this intentionally unanswered request.
				}
			},
			1
		);

		assertThatThrownBy(() -> adapter.getRefundStatus(UUID.randomUUID()))
			.isInstanceOf(PaymentRefundStatusQueryException.class)
			.satisfies(exception -> assertThat(exception.getCause())
				.isInstanceOfSatisfying(StatusRuntimeException.class, cause ->
					assertThat(cause.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED)));
	}

	@Test
	@DisplayName("열린 서킷은 실제 RPC 없이 애플리케이션 예외를 반환한다")
	void getRefundStatus_openCircuit_wrapsCause() throws IOException {
		PaymentRefundGrpcResilience resilience = resilience(1);
		resilience.circuitBreaker().transitionToOpenState();
		PaymentRefundGrpcClientAdapter adapter = adapterReturning(
			GetRefundStatusResponse.getDefaultInstance(),
			resilience
		);

		assertThatThrownBy(() -> adapter.getRefundStatus(UUID.randomUUID()))
			.isInstanceOf(PaymentRefundStatusQueryException.class)
			.hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
	}

	@Test
	@DisplayName("가득 찬 벌크헤드는 실제 RPC 없이 애플리케이션 예외를 반환한다")
	void getRefundStatus_fullBulkhead_wrapsCause() throws IOException {
		PaymentRefundGrpcResilience resilience = resilience(1);
		assertThat(resilience.bulkhead().tryAcquirePermission()).isTrue();
		PaymentRefundGrpcClientAdapter adapter = adapterReturning(
			GetRefundStatusResponse.getDefaultInstance(),
			resilience
		);

		try {
			assertThatThrownBy(() -> adapter.getRefundStatus(UUID.randomUUID()))
				.isInstanceOf(PaymentRefundStatusQueryException.class)
				.hasCauseInstanceOf(io.github.resilience4j.bulkhead.BulkheadFullException.class);
		} finally {
			resilience.bulkhead().releasePermission();
		}
	}

	@Test
	@DisplayName("완료 응답의 환불 시각이 없으면 조회 실패로 처리한다")
	void getRefundStatus_completedWithoutTimestamp_throwsQueryException() throws IOException {
		PaymentRefundGrpcClientAdapter adapter = adapterReturning(GetRefundStatusResponse.newBuilder()
			.setStatus(RefundStatus.REFUND_STATUS_COMPLETED)
			.build());

		assertThatThrownBy(() -> adapter.getRefundStatus(UUID.randomUUID()))
			.isInstanceOf(PaymentRefundStatusQueryException.class)
			.hasCauseInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("완료 응답의 환불 시각 형식이 잘못되면 조회 실패로 처리한다")
	void getRefundStatus_completedWithMalformedTimestamp_throwsQueryException() throws IOException {
		PaymentRefundGrpcClientAdapter adapter = adapterReturning(GetRefundStatusResponse.newBuilder()
			.setStatus(RefundStatus.REFUND_STATUS_COMPLETED)
			.setRefundedAt("2026-07-11T25:00:00")
			.build());

		assertThatThrownBy(() -> adapter.getRefundStatus(UUID.randomUUID()))
			.isInstanceOf(PaymentRefundStatusQueryException.class)
			.hasCauseInstanceOf(java.time.format.DateTimeParseException.class);
	}

	private PaymentRefundGrpcClientAdapter adapterWith(
		PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase service
	) throws IOException {
		return adapterWith(service, 2_000);
	}

	private PaymentRefundGrpcClientAdapter adapterWith(
		PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase service,
		int deadlineMs
	) throws IOException {
		String serverName = InProcessServerBuilder.generateName();
		server = InProcessServerBuilder.forName(serverName)
			.directExecutor()
			.addService(service)
			.build()
			.start();
		channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
		return new PaymentRefundGrpcClientAdapter(
			PaymentRefundQueryServiceGrpc.newBlockingStub(channel),
			resilience(1),
			deadlineMs
		);
	}

	private PaymentRefundGrpcClientAdapter adapterReturning(GetRefundStatusResponse response) throws IOException {
		return adapterReturning(response, resilience(1));
	}

	private PaymentRefundGrpcClientAdapter adapterReturning(
		GetRefundStatusResponse response,
		PaymentRefundGrpcResilience resilience
	) throws IOException {
		return adapterWith(new PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase() {
			@Override
			public void getRefundStatus(GetRefundStatusRequest request, StreamObserver<GetRefundStatusResponse> observer) {
				observer.onNext(response);
				observer.onCompleted();
			}
		}, resilience);
	}

	private PaymentRefundGrpcClientAdapter adapterFailingWith(StatusRuntimeException failure) throws IOException {
		return adapterWith(new PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase() {
			@Override
			public void getRefundStatus(GetRefundStatusRequest request, StreamObserver<GetRefundStatusResponse> observer) {
				observer.onError(failure);
			}
		});
	}

	private PaymentRefundGrpcClientAdapter adapterWith(
		PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase service,
		PaymentRefundGrpcResilience resilience
	) throws IOException {
		String serverName = InProcessServerBuilder.generateName();
		server = InProcessServerBuilder.forName(serverName)
			.directExecutor()
			.addService(service)
			.build()
			.start();
		channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
		return new PaymentRefundGrpcClientAdapter(
			PaymentRefundQueryServiceGrpc.newBlockingStub(channel),
			resilience,
			2_000
		);
	}

	private PaymentRefundGrpcResilience resilience(int maxConcurrentCalls) {
		return TestResilienceFactory.create(maxConcurrentCalls);
	}
}
