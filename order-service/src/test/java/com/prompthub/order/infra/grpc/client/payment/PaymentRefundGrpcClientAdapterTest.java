package com.prompthub.order.infra.grpc.client.payment;

import com.prompthub.grpc.payment.refund.v1.GetRefundStatusRequest;
import com.prompthub.grpc.payment.refund.v1.GetRefundStatusResponse;
import com.prompthub.grpc.payment.refund.v1.PaymentRefundQueryServiceGrpc;
import com.prompthub.grpc.payment.refund.v1.RefundStatus;
import com.prompthub.order.application.dto.PaymentRefundStatusResult;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
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
	@DisplayName("gRPC 통신 실패는 호출자에게 전달해 재조정에서 미확정으로 처리한다")
	void getRefundStatus_unavailable_throwsException() throws IOException {
		PaymentRefundGrpcClientAdapter adapter = adapterWith(new PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase() {
			@Override
			public void getRefundStatus(GetRefundStatusRequest request, StreamObserver<GetRefundStatusResponse> observer) {
				observer.onError(Status.UNAVAILABLE.asRuntimeException());
			}
		});

		assertThatThrownBy(() -> adapter.getRefundStatus(UUID.randomUUID()))
			.isInstanceOf(io.grpc.StatusRuntimeException.class);
	}

	private PaymentRefundGrpcClientAdapter adapterWith(
		PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceImplBase service
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
			2_000
		);
	}
}
