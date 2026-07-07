package com.prompthub.order.infra.grpc.client.product;

import com.prompthub.exception.BusinessException;
import com.prompthub.grpc.product.v1.GetCartSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetCartSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetProductContentRequest;
import com.prompthub.grpc.product.v1.GetProductContentResponse;
import com.prompthub.grpc.product.v1.ProductCartSnapshotMessage;
import com.prompthub.grpc.product.v1.ProductInternalServiceGrpc;
import com.prompthub.order.application.dto.ProductCartSnapshot;
import com.prompthub.order.application.dto.ProductContent;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.grpc.Server;

class ProductGrpcClientAdapterTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SECOND_PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID BUYER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

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
	void mapsOrderSnapshotsResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductInternalServiceGrpc.ProductInternalServiceImplBase() {
			@Override
			public void getOrderSnapshots(
				GetOrderSnapshotsRequest request,
				StreamObserver<GetOrderSnapshotsResponse> responseObserver
			) {
				assertThat(request.getProductIdsList()).containsExactly(PRODUCT_ID.toString(), SECOND_PRODUCT_ID.toString());
				responseObserver.onNext(GetOrderSnapshotsResponse.newBuilder()
					.addProducts(orderSnapshot(PRODUCT_ID))
					.addProducts(orderSnapshot(SECOND_PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		});

		List<ProductOrderSnapshot> snapshots = adapter.getOrderSnapshots(List.of(PRODUCT_ID, SECOND_PRODUCT_ID));

		assertThat(snapshots).extracting(ProductOrderSnapshot::productId)
			.containsExactly(PRODUCT_ID, SECOND_PRODUCT_ID);
		assertThat(snapshots.getFirst().sellerId()).isEqualTo(SELLER_ID);
		assertThat(snapshots.getFirst().title()).isEqualTo("테스트 상품");
		assertThat(snapshots.getFirst().productType()).isEqualTo("PROMPT");
		assertThat(snapshots.getFirst().amount()).isEqualTo(10000);
		assertThat(snapshots.getFirst().model()).isEqualTo("GPT-4");
	}

	@Test
	void mapsCartSnapshotResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductInternalServiceGrpc.ProductInternalServiceImplBase() {
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				assertThat(request.getProductIdsList()).containsExactly(PRODUCT_ID.toString());
				responseObserver.onNext(GetCartSnapshotsResponse.newBuilder()
					.addProducts(cartSnapshot(PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		});

		ProductCartSnapshot snapshot = adapter.getCartSnapshot(PRODUCT_ID);

		assertThat(snapshot.productId()).isEqualTo(PRODUCT_ID);
		assertThat(snapshot.sellerId()).isEqualTo(SELLER_ID);
		assertThat(snapshot.sellerNickname()).isEqualTo("판매자");
		assertThat(snapshot.status()).isEqualTo("ON_SALE");
	}

	@Test
	void mapsCartSnapshotsResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductInternalServiceGrpc.ProductInternalServiceImplBase() {
			@Override
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				assertThat(request.getProductIdsList()).containsExactly(PRODUCT_ID.toString(), SECOND_PRODUCT_ID.toString());
				responseObserver.onNext(GetCartSnapshotsResponse.newBuilder()
					.addProducts(cartSnapshot(PRODUCT_ID))
					.addProducts(cartSnapshot(SECOND_PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		});

		List<ProductCartSnapshot> snapshots = adapter.getCartSnapshots(List.of(PRODUCT_ID, SECOND_PRODUCT_ID));

		assertThat(snapshots).extracting(ProductCartSnapshot::productId)
			.containsExactly(PRODUCT_ID, SECOND_PRODUCT_ID);
	}

	@Test
	void mapsProductContentResponse() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductInternalServiceGrpc.ProductInternalServiceImplBase() {
			@Override
			public void getProductContent(
				GetProductContentRequest request,
				StreamObserver<GetProductContentResponse> responseObserver
			) {
				assertThat(request.getProductId()).isEqualTo(PRODUCT_ID.toString());
				responseObserver.onNext(GetProductContentResponse.newBuilder()
					.setProductId(PRODUCT_ID.toString())
					.setContent("구매 콘텐츠")
					.build());
				responseObserver.onCompleted();
			}
		});

		ProductContent content = adapter.getProductContent(PRODUCT_ID);

		assertThat(content.productId()).isEqualTo(PRODUCT_ID);
		assertThat(content.content()).isEqualTo("구매 콘텐츠");
	}


	@Test
	void mapsGrpcFailureToBusinessException() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductInternalServiceGrpc.ProductInternalServiceImplBase() {
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
			}
		});

		assertThatThrownBy(() -> adapter.getCartSnapshot(PRODUCT_ID))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("gRPC 서버 지연으로 인해 Deadline(Timeout) 초과 시 BusinessException 예외가 발생한다")
	void mapsDeadlineExceededToBusinessException() throws IOException {
		ProductGrpcClientAdapter adapter = adapterWith(new ProductInternalServiceGrpc.ProductInternalServiceImplBase() {
			@Override
			public void getCartSnapshots(
				GetCartSnapshotsRequest request,
				StreamObserver<GetCartSnapshotsResponse> responseObserver
			) {
				try {
					Thread.sleep(500); // 500ms 지연
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				responseObserver.onNext(GetCartSnapshotsResponse.newBuilder()
					.addProducts(cartSnapshot(PRODUCT_ID))
					.build());
				responseObserver.onCompleted();
			}
		}, 100); // 클라이언트 deadline을 100ms로 설정

		assertThatThrownBy(() -> adapter.getCartSnapshot(PRODUCT_ID))
			.isInstanceOf(BusinessException.class);
	}

	private ProductGrpcClientAdapter adapterWith(ProductInternalServiceGrpc.ProductInternalServiceImplBase service)
		throws IOException {
		return adapterWith(service, 2000);
	}

	private ProductGrpcClientAdapter adapterWith(ProductInternalServiceGrpc.ProductInternalServiceImplBase service, int deadlineMs)
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
		return new ProductGrpcClientAdapter(ProductInternalServiceGrpc.newBlockingStub(channel), deadlineMs);
	}

	private com.prompthub.grpc.product.v1.ProductOrderSnapshot orderSnapshot(UUID productId) {
		return com.prompthub.grpc.product.v1.ProductOrderSnapshot.newBuilder()
			.setProductId(productId.toString())
			.setSellerId(SELLER_ID.toString())
			.setTitle("테스트 상품")
			.setProductType("PROMPT")
			.setAmount(10000)
			.setModel("GPT-4")
			.build();
	}

	private ProductCartSnapshotMessage cartSnapshot(UUID productId) {
		return ProductCartSnapshotMessage.newBuilder()
			.setProductId(productId.toString())
			.setSellerId(SELLER_ID.toString())
			.setSellerNickname("판매자")
			.setTitle("테스트 상품")
			.setProductType("PROMPT")
			.setAmount(10000)
			.setThumbnailUrl("https://example.com/thumb.png")
			.build();
	}
}
