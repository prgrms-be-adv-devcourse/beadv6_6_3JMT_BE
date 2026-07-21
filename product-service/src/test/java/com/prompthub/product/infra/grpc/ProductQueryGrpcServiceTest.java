package com.prompthub.product.infra.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.grpc.GetCartSnapshotsRequest;
import com.prompthub.product.grpc.GetCartSnapshotsResponse;
import com.prompthub.product.grpc.GetOrderSnapshotsRequest;
import com.prompthub.product.grpc.GetOrderSnapshotsResponse;
import com.prompthub.product.grpc.GetProductContentRequest;
import com.prompthub.product.grpc.GetProductContentResponse;
import com.prompthub.product.grpc.ProductContentPurpose;
import com.prompthub.product.grpc.ProductContentResult;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductQueryGrpcServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Mock
	private ProductInternalUseCase productInternalUseCase;

	@Mock
	private StreamObserver<GetOrderSnapshotsResponse> orderSnapshotsObserver;

	@Mock
	private StreamObserver<GetCartSnapshotsResponse> cartSnapshotsObserver;

	@Mock
	private StreamObserver<GetProductContentResponse> contentObserver;

	@InjectMocks
	private ProductQueryGrpcService grpcService;

	@Test
	@DisplayName("GetOrderSnapshots는 기존 매핑을 그대로 유지한다")
	void getOrderSnapshots_mapsResponse() {
		given(productInternalUseCase.getOrderSnapshots(List.of(PRODUCT_ID)))
			.willReturn(List.of(new ProductOrderSnapshotResponse(PRODUCT_ID, SELLER_ID, "제목", "PROMPT", 5000, "GPT-4o")));

		GetOrderSnapshotsRequest request = GetOrderSnapshotsRequest.newBuilder()
			.addProductIds(PRODUCT_ID.toString())
			.build();
		grpcService.getOrderSnapshots(request, orderSnapshotsObserver);

		ArgumentCaptor<GetOrderSnapshotsResponse> captor = ArgumentCaptor.forClass(GetOrderSnapshotsResponse.class);
		then(orderSnapshotsObserver).should().onNext(captor.capture());
		assertThat(captor.getValue().getProducts(0).getTitle()).isEqualTo("제목");
		then(orderSnapshotsObserver).should().onCompleted();
	}

	@Test
	@DisplayName("GetCartSnapshots는 기존 매핑을 그대로 유지한다")
	void getCartSnapshots_mapsResponse() {
		given(productInternalUseCase.getCartSnapshots(List.of(PRODUCT_ID)))
			.willReturn(List.of(new ProductCartSnapshotResponse(
				PRODUCT_ID, SELLER_ID, "제목", "PROMPT", 5000, "https://thumb", "닉네임", "ON_SALE")));

		GetCartSnapshotsRequest request = GetCartSnapshotsRequest.newBuilder()
			.addProductIds(PRODUCT_ID.toString())
			.build();
		grpcService.getCartSnapshots(request, cartSnapshotsObserver);

		ArgumentCaptor<GetCartSnapshotsResponse> captor = ArgumentCaptor.forClass(GetCartSnapshotsResponse.class);
		then(cartSnapshotsObserver).should().onNext(captor.capture());
		assertThat(captor.getValue().getProducts(0).getSellerNickname()).isEqualTo("닉네임");
		then(cartSnapshotsObserver).should().onCompleted();
	}

	@Nested
	@DisplayName("GetProductContent — purpose 분기")
	class GetProductContentPurposeRouting {

		@Test
		@DisplayName("ORDER_SNAPSHOT 요청은 order_snapshot 결과만 반환한다")
		void orderSnapshotPurpose_returnsOnlyOrderSnapshotResults() {
			given(productInternalUseCase.getOrderSnapshots(List.of(PRODUCT_ID)))
				.willReturn(List.of(new ProductOrderSnapshotResponse(PRODUCT_ID, SELLER_ID, "제목", "PROMPT", 5000, "GPT-4o")));

			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.addProductIds(PRODUCT_ID.toString())
				.setPurpose(ProductContentPurpose.ORDER_SNAPSHOT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			GetProductContentResponse response = captureResponse();
			assertThat(response.getResultsList()).hasSize(1);
			assertThat(response.getResults(0).getPayloadCase()).isEqualTo(ProductContentResult.PayloadCase.ORDER_SNAPSHOT);
			assertThat(response.getResults(0).getOrderSnapshot().getTitle()).isEqualTo("제목");
			assertThat(response.getProductId()).isEmpty();
			assertThat(response.getContent()).isEmpty();
		}

		@Test
		@DisplayName("CART_SNAPSHOT 요청은 cart_snapshot 결과만 반환한다")
		void cartSnapshotPurpose_returnsOnlyCartSnapshotResults() {
			given(productInternalUseCase.getCartSnapshots(List.of(PRODUCT_ID)))
				.willReturn(List.of(new ProductCartSnapshotResponse(
					PRODUCT_ID, SELLER_ID, "제목", "PROMPT", 5000, "https://thumb", "닉네임", "ON_SALE")));

			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.addProductIds(PRODUCT_ID.toString())
				.setPurpose(ProductContentPurpose.CART_SNAPSHOT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			GetProductContentResponse response = captureResponse();
			assertThat(response.getResultsList()).hasSize(1);
			assertThat(response.getResults(0).getPayloadCase()).isEqualTo(ProductContentResult.PayloadCase.CART_SNAPSHOT);
			assertThat(response.getResults(0).getCartSnapshot().getSellerNickname()).isEqualTo("닉네임");
		}

		@Test
		@DisplayName("PURCHASED_CONTENT 요청은 purchased_content 결과 1건만 반환한다")
		void purchasedContentPurpose_returnsSinglePurchasedContentResult() {
			given(productInternalUseCase.getProductContent(PRODUCT_ID))
				.willReturn(new ProductContentResponse(PRODUCT_ID, "프롬프트 본문"));

			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.setProductId(PRODUCT_ID.toString())
				.setPurpose(ProductContentPurpose.PURCHASED_CONTENT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			GetProductContentResponse response = captureResponse();
			assertThat(response.getResultsList()).hasSize(1);
			assertThat(response.getResults(0).getPayloadCase()).isEqualTo(ProductContentResult.PayloadCase.PURCHASED_CONTENT);
			assertThat(response.getResults(0).getPurchasedContent().getContent()).isEqualTo("프롬프트 본문");
			assertThat(response.getProductId()).isEmpty();
			assertThat(response.getContent()).isEmpty();
		}

		@Test
		@DisplayName("purpose 없이(구형 UNSPECIFIED) product_id로 요청하면 구형 필드와 purchased_content를 함께 채운다")
		void unspecifiedPurposeWithProductId_legacyCompat_fillsOldFieldsAndPurchasedContent() {
			given(productInternalUseCase.getProductContent(PRODUCT_ID))
				.willReturn(new ProductContentResponse(PRODUCT_ID, "프롬프트 본문"));

			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.setProductId(PRODUCT_ID.toString())
				.build();
			grpcService.getProductContent(request, contentObserver);

			GetProductContentResponse response = captureResponse();
			assertThat(response.getProductId()).isEqualTo(PRODUCT_ID.toString());
			assertThat(response.getContent()).isEqualTo("프롬프트 본문");
			assertThat(response.getResultsList()).hasSize(1);
			assertThat(response.getResults(0).getPurchasedContent().getContent()).isEqualTo("프롬프트 본문");
		}

		@Test
		@DisplayName("ORDER_SNAPSHOT인데 product_id가 채워져 있으면 INVALID_ARGUMENT")
		void orderSnapshotPurposeWithProductIdSet_invalidArgument() {
			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.setProductId(PRODUCT_ID.toString())
				.addProductIds(PRODUCT_ID.toString())
				.setPurpose(ProductContentPurpose.ORDER_SNAPSHOT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			assertStatusCode(Status.Code.INVALID_ARGUMENT);
		}

		@Test
		@DisplayName("PURCHASED_CONTENT인데 product_ids가 채워져 있으면 INVALID_ARGUMENT")
		void purchasedContentPurposeWithProductIdsSet_invalidArgument() {
			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.addProductIds(PRODUCT_ID.toString())
				.setPurpose(ProductContentPurpose.PURCHASED_CONTENT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			assertStatusCode(Status.Code.INVALID_ARGUMENT);
		}

		@Test
		@DisplayName("잘못된 UUID 형식이면 INVALID_ARGUMENT")
		void invalidUuid_invalidArgument() {
			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.setProductId("not-a-uuid")
				.setPurpose(ProductContentPurpose.PURCHASED_CONTENT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			assertStatusCode(Status.Code.INVALID_ARGUMENT);
		}

		@Test
		@DisplayName("구매 콘텐츠 대상이 없으면 NOT_FOUND")
		void purchasedContentNotFound_notFound() {
			given(productInternalUseCase.getProductContent(PRODUCT_ID))
				.willThrow(new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.setProductId(PRODUCT_ID.toString())
				.setPurpose(ProductContentPurpose.PURCHASED_CONTENT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			assertStatusCode(Status.Code.NOT_FOUND);
		}

		@Test
		@DisplayName("주문/장바구니 결과에는 content나 purchased_content가 채워지지 않는다")
		void orderAndCartResults_neverPopulateContentOrPurchasedContent() {
			given(productInternalUseCase.getOrderSnapshots(List.of(PRODUCT_ID)))
				.willReturn(List.of(new ProductOrderSnapshotResponse(PRODUCT_ID, SELLER_ID, "제목", "PROMPT", 5000, "GPT-4o")));

			GetProductContentRequest request = GetProductContentRequest.newBuilder()
				.addProductIds(PRODUCT_ID.toString())
				.setPurpose(ProductContentPurpose.ORDER_SNAPSHOT)
				.build();
			grpcService.getProductContent(request, contentObserver);

			GetProductContentResponse response = captureResponse();
			assertThat(response.getResults(0).getPayloadCase())
				.isNotEqualTo(ProductContentResult.PayloadCase.PURCHASED_CONTENT);
		}

		private GetProductContentResponse captureResponse() {
			ArgumentCaptor<GetProductContentResponse> captor = ArgumentCaptor.forClass(GetProductContentResponse.class);
			then(contentObserver).should().onNext(captor.capture());
			then(contentObserver).should().onCompleted();
			return captor.getValue();
		}

		private void assertStatusCode(Status.Code expected) {
			ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
			then(contentObserver).should().onError(captor.capture());
			assertThat(captor.getValue()).isInstanceOf(StatusRuntimeException.class);
			assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode()).isEqualTo(expected);
		}
	}
}
