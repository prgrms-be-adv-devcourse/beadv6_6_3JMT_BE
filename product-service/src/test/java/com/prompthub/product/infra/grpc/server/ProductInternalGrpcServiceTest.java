package com.prompthub.product.infra.grpc.server;

import com.prompthub.grpc.product.v1.GetCartSnapshotRequest;
import com.prompthub.grpc.product.v1.GetCartSnapshotResponse;
import com.prompthub.grpc.product.v1.GetCartSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetCartSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsRequest;
import com.prompthub.grpc.product.v1.GetOrderSnapshotsResponse;
import com.prompthub.grpc.product.v1.GetProductContentRequest;
import com.prompthub.grpc.product.v1.GetProductContentResponse;
import com.prompthub.grpc.product.v1.UpsertReviewRequest;
import com.prompthub.grpc.product.v1.UpsertReviewResponse;
import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductInternalGrpcServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SECOND_PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID BUYER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	@Mock
	private ProductInternalUseCase productInternalUseCase;

	@Test
	void mapsOrderSnapshots() {
		ProductInternalGrpcService service = new ProductInternalGrpcService(productInternalUseCase);
		given(productInternalUseCase.getOrderSnapshots(List.of(PRODUCT_ID, SECOND_PRODUCT_ID)))
			.willReturn(List.of(orderSnapshot(PRODUCT_ID), orderSnapshot(SECOND_PRODUCT_ID)));
		TestObserver<GetOrderSnapshotsResponse> observer = new TestObserver<>();

		service.getOrderSnapshots(GetOrderSnapshotsRequest.newBuilder()
			.addProductIds(PRODUCT_ID.toString())
			.addProductIds(SECOND_PRODUCT_ID.toString())
			.build(), observer);

		assertThat(observer.completed).isTrue();
		assertThat(observer.value.getProductsList()).hasSize(2);
		assertThat(observer.value.getProducts(0).getProductId()).isEqualTo(PRODUCT_ID.toString());
		assertThat(observer.value.getProducts(0).getSellerId()).isEqualTo(SELLER_ID.toString());
	}

	@Test
	void mapsCartSnapshot() {
		ProductInternalGrpcService service = new ProductInternalGrpcService(productInternalUseCase);
		given(productInternalUseCase.getCartSnapshot(PRODUCT_ID)).willReturn(cartSnapshot(PRODUCT_ID));
		TestObserver<GetCartSnapshotResponse> observer = new TestObserver<>();

		service.getCartSnapshot(GetCartSnapshotRequest.newBuilder()
			.setProductId(PRODUCT_ID.toString())
			.build(), observer);

		assertThat(observer.completed).isTrue();
		assertThat(observer.value.getProductId()).isEqualTo(PRODUCT_ID.toString());
		assertThat(observer.value.getSellerNickname()).isEqualTo("판매자");
		assertThat(observer.value.getStatus()).isEqualTo("ON_SALE");
	}

	@Test
	void mapsCartSnapshots() {
		ProductInternalGrpcService service = new ProductInternalGrpcService(productInternalUseCase);
		given(productInternalUseCase.getCartSnapshots(List.of(PRODUCT_ID, SECOND_PRODUCT_ID)))
			.willReturn(List.of(cartSnapshot(PRODUCT_ID), cartSnapshot(SECOND_PRODUCT_ID)));
		TestObserver<GetCartSnapshotsResponse> observer = new TestObserver<>();

		service.getCartSnapshots(GetCartSnapshotsRequest.newBuilder()
			.addProductIds(PRODUCT_ID.toString())
			.addProductIds(SECOND_PRODUCT_ID.toString())
			.build(), observer);

		assertThat(observer.completed).isTrue();
		assertThat(observer.value.getProductsList()).hasSize(2);
	}

	@Test
	void mapsProductContent() {
		ProductInternalGrpcService service = new ProductInternalGrpcService(productInternalUseCase);
		given(productInternalUseCase.getProductContent(PRODUCT_ID))
			.willReturn(new ProductContentResponse(PRODUCT_ID, "구매 콘텐츠"));
		TestObserver<GetProductContentResponse> observer = new TestObserver<>();

		service.getProductContent(GetProductContentRequest.newBuilder()
			.setProductId(PRODUCT_ID.toString())
			.build(), observer);

		assertThat(observer.completed).isTrue();
		assertThat(observer.value.getProductId()).isEqualTo(PRODUCT_ID.toString());
		assertThat(observer.value.getContent()).isEqualTo("구매 콘텐츠");
	}

	@Test
	void delegatesUpsertReview() {
		ProductInternalGrpcService service = new ProductInternalGrpcService(productInternalUseCase);
		TestObserver<UpsertReviewResponse> observer = new TestObserver<>();

		service.upsertReview(UpsertReviewRequest.newBuilder()
			.setBuyerId(BUYER_ID.toString())
			.setProductId(PRODUCT_ID.toString())
			.setRating(4)
			.build(), observer);

		assertThat(observer.completed).isTrue();
		then(productInternalUseCase).should().upsertReview(BUYER_ID, PRODUCT_ID, 4);
	}

	private ProductOrderSnapshotResponse orderSnapshot(UUID productId) {
		return new ProductOrderSnapshotResponse(productId, SELLER_ID, "테스트 상품", "PROMPT", 10000);
	}

	private ProductCartSnapshotResponse cartSnapshot(UUID productId) {
		return new ProductCartSnapshotResponse(
			productId,
			"테스트 상품",
			"PROMPT",
			10000,
			"https://example.com/thumb.png",
			SELLER_ID,
			"판매자",
			"ON_SALE"
		);
	}

	private static class TestObserver<T> implements StreamObserver<T> {

		private T value;
		private Throwable error;
		private boolean completed;

		@Override
		public void onNext(T value) {
			this.value = value;
		}

		@Override
		public void onError(Throwable throwable) {
			this.error = throwable;
		}

		@Override
		public void onCompleted() {
			this.completed = true;
		}
	}
}
