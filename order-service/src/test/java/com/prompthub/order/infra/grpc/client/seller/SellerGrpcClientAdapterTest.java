package com.prompthub.order.infra.grpc.client.seller;

import com.prompthub.order.grpc.seller.SellerBatchQueryRequest;
import com.prompthub.order.grpc.seller.SellerBatchQueryResponse;
import com.prompthub.order.grpc.seller.SellerInfo;
import com.prompthub.order.grpc.seller.SellerQueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import io.grpc.Server;

class SellerGrpcClientAdapterTest {

	private static final UUID SELLER_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private Server server;
	private ManagedChannel channel;

	@AfterEach
	void tearDown() {
		if (channel != null) {
			channel.shutdownNow();
		}
		if (server != null) {
			server.shutdownNow();
		}
	}

	@Test
	@DisplayName("판매자 ID 목록으로 닉네임을 조회한다")
	void getSellerNicknames_success() throws IOException {
		SellerGrpcClientAdapter adapter = adapterWith(new SellerQueryServiceGrpc.SellerQueryServiceImplBase() {
			@Override
			public void findSellers(
				SellerBatchQueryRequest request,
				StreamObserver<SellerBatchQueryResponse> responseObserver
			) {
				assertThat(request.getSellerIdsList()).containsExactly(
					SELLER_ID_1.toString(), SELLER_ID_2.toString()
				);
				responseObserver.onNext(SellerBatchQueryResponse.newBuilder()
					.addSellers(SellerInfo.newBuilder()
						.setSellerId(SELLER_ID_1.toString())
						.setSellerName("판매자A")
						.build())
					.addSellers(SellerInfo.newBuilder()
						.setSellerId(SELLER_ID_2.toString())
						.setSellerName("판매자B")
						.build())
					.build());
				responseObserver.onCompleted();
			}
		});

		Map<UUID, String> nicknames = adapter.getSellerNicknames(List.of(SELLER_ID_1, SELLER_ID_2));

		assertThat(nicknames).hasSize(2);
		assertThat(nicknames.get(SELLER_ID_1)).isEqualTo("판매자A");
		assertThat(nicknames.get(SELLER_ID_2)).isEqualTo("판매자B");
	}

	@Test
	@DisplayName("빈 목록을 전달하면 gRPC 호출 없이 빈 맵을 반환한다")
	void getSellerNicknames_emptyList_returnsEmptyMap() throws IOException {
		SellerGrpcClientAdapter adapter = adapterWith(new SellerQueryServiceGrpc.SellerQueryServiceImplBase() {});

		Map<UUID, String> nicknames = adapter.getSellerNicknames(List.of());

		assertThat(nicknames).isEmpty();
	}

	@Test
	@DisplayName("gRPC 호출 실패 시 빈 맵을 반환한다")
	void getSellerNicknames_grpcFailure_returnsEmptyMap() throws IOException {
		SellerGrpcClientAdapter adapter = adapterWith(new SellerQueryServiceGrpc.SellerQueryServiceImplBase() {
			@Override
			public void findSellers(
				SellerBatchQueryRequest request,
				StreamObserver<SellerBatchQueryResponse> responseObserver
			) {
				responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
			}
		});

		Map<UUID, String> nicknames = adapter.getSellerNicknames(List.of(SELLER_ID_1));

		assertThat(nicknames).isEmpty();
	}

	@Test
	@DisplayName("빈 이름의 판매자는 결과에서 제외된다")
	void getSellerNicknames_emptyName_filtered() throws IOException {
		SellerGrpcClientAdapter adapter = adapterWith(new SellerQueryServiceGrpc.SellerQueryServiceImplBase() {
			@Override
			public void findSellers(
				SellerBatchQueryRequest request,
				StreamObserver<SellerBatchQueryResponse> responseObserver
			) {
				responseObserver.onNext(SellerBatchQueryResponse.newBuilder()
					.addSellers(SellerInfo.newBuilder()
						.setSellerId(SELLER_ID_1.toString())
						.setSellerName("판매자A")
						.build())
					.addSellers(SellerInfo.newBuilder()
						.setSellerId(SELLER_ID_2.toString())
						.setSellerName("")
						.build())
					.build());
				responseObserver.onCompleted();
			}
		});

		Map<UUID, String> nicknames = adapter.getSellerNicknames(List.of(SELLER_ID_1, SELLER_ID_2));

		assertThat(nicknames).hasSize(1);
		assertThat(nicknames.get(SELLER_ID_1)).isEqualTo("판매자A");
		assertThat(nicknames).doesNotContainKey(SELLER_ID_2);
	}

	private SellerGrpcClientAdapter adapterWith(SellerQueryServiceGrpc.SellerQueryServiceImplBase service)
		throws IOException {
		String serverName = InProcessServerBuilder.generateName();
		server = InProcessServerBuilder.forName(serverName)
			.directExecutor()
			.addService(service)
			.build()
			.start();
		channel = InProcessChannelBuilder.forName(serverName)
			.directExecutor()
			.build();
		return new SellerGrpcClientAdapter(SellerQueryServiceGrpc.newBlockingStub(channel), 2000);
	}
}
