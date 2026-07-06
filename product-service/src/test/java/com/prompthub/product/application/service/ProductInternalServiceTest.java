package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductInternalServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ReviewRepository reviewRepository;

	@Mock
	private SellerClient sellerClient;

	@InjectMocks
	private ProductInternalService productInternalService;

	@Nested
	@DisplayName("장바구니 스냅샷 조회")
	class GetCartSnapshots {

		@Test
		@DisplayName("ON_SALE 상품만 조회하여 판매자 닉네임과 함께 반환한다")
		void getCartSnapshots_onSaleOnly() {
			Product product = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			given(productRepository.findOnSaleByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "프롬프트상점", null, "ACTIVE"));

			List<ProductCartSnapshotResponse> result = productInternalService.getCartSnapshots(List.of(PRODUCT_ID));

			assertThat(result).hasSize(1);
			ProductCartSnapshotResponse snapshot = result.get(0);
			assertThat(snapshot.productId()).isEqualTo(PRODUCT_ID);
			assertThat(snapshot.sellerId()).isEqualTo(SELLER_ID);
			assertThat(snapshot.sellerNickname()).isEqualTo("프롬프트상점");
			assertThat(snapshot.productTitle()).isEqualTo("면접 답변 프롬프트");
			assertThat(snapshot.productType()).isEqualTo("PROMPT");
			assertThat(snapshot.productAmount()).isEqualTo(15000);
			assertThat(snapshot.productStatus()).isEqualTo("ON_SALE");
		}

		@Test
		@DisplayName("단건 장바구니 스냅샷은 판매 중이 아닌 상품 상태도 그대로 반환한다")
		void getCartSnapshot_preservesNonOnSaleStatus() {
			Product product = product(PRODUCT_ID, SELLER_ID, ProductStatus.STOPPED);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "프롬프트상점", null, "ACTIVE"));

			ProductCartSnapshotResponse result = productInternalService.getCartSnapshot(PRODUCT_ID);

			assertThat(result.productId()).isEqualTo(PRODUCT_ID);
			assertThat(result.sellerId()).isEqualTo(SELLER_ID);
			assertThat(result.sellerNickname()).isEqualTo("프롬프트상점");
			assertThat(result.productStatus()).isEqualTo("STOPPED");
		}

		@Test
		@DisplayName("ON_SALE 아닌 상품이 요청되면 응답에서 제외된다")
		void getCartSnapshots_excludesNonOnSale() {
			given(productRepository.findOnSaleByIdIn(List.of(PRODUCT_ID))).willReturn(List.of());

			List<ProductCartSnapshotResponse> result = productInternalService.getCartSnapshots(List.of(PRODUCT_ID));

			assertThat(result).isEmpty();
			then(sellerClient).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("동일 판매자의 상품이 여러 개일 때 sellerClient를 한 번만 호출한다")
		void getCartSnapshots_deduplicatesSellerCall() {
			UUID productId2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
			Product product1 = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			Product product2 = product(productId2, SELLER_ID, ProductStatus.ON_SALE);
			given(productRepository.findOnSaleByIdIn(List.of(PRODUCT_ID, productId2)))
				.willReturn(List.of(product1, product2));
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "프롬프트상점", null, "ACTIVE"));

			List<ProductCartSnapshotResponse> result =
				productInternalService.getCartSnapshots(List.of(PRODUCT_ID, productId2));

			assertThat(result).hasSize(2);
			then(sellerClient).should().getSellerInfo(SELLER_ID);
		}
	}

	private Product product(UUID id, UUID sellerId, ProductStatus status) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "sellerId", sellerId);
		ReflectionTestUtils.setField(product, "name", "면접 답변 프롬프트");
		ReflectionTestUtils.setField(product, "productType", "PROMPT");
		ReflectionTestUtils.setField(product, "amount", 15000);
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}

	private <T> T instantiate(Class<T> type) {
		try {
			java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("테스트 fixture 생성에 실패했습니다.", exception);
		}
	}
}
