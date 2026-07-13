package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.argThat;
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
	@DisplayName("셀러 상품 수/판매수 조회")
	class GetProductCount {

		@Test
		@DisplayName("product_count는 family 수, sales_count는 셀러 판매수 합으로 반환한다")
		void getProductCount_returnsFamilyCountAndSalesSum() {
			given(productRepository.countFamiliesBySellerId(SELLER_ID)).willReturn(3L);
			given(productRepository.sumSalesCountBySellerId(SELLER_ID)).willReturn(1240L);

			var response = productInternalService.getProductCount(SELLER_ID);

			assertThat(response.sellerId()).isEqualTo(SELLER_ID);
			assertThat(response.productCount()).isEqualTo(3L);
			assertThat(response.salesCount()).isEqualTo(1240L);
		}
	}

	@Nested
	@DisplayName("장바구니 스냅샷 조회")
	class GetCartSnapshots {

		@Test
		@DisplayName("ON_SALE 상품만 조회하여 판매자 닉네임과 함께 반환한다")
		void getCartSnapshots_onSaleOnly() {
			Product product = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
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
		}

		@Test
		@DisplayName("ON_SALE 아닌 상품이 요청되면 응답에서 제외된다")
		void getCartSnapshots_excludesNonOnSale() {
			Product product = product(PRODUCT_ID, SELLER_ID, ProductStatus.DRAFT);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));

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
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID, productId2)))
				.willReturn(List.of(product1, product2));
			given(productRepository.findAllByFamilyRootIds(argThat(ids ->
				ids != null && java.util.Set.copyOf(ids).equals(java.util.Set.of(PRODUCT_ID, productId2)))))
				.willReturn(List.of(product1, product2));
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "프롬프트상점", null, "ACTIVE"));

			List<ProductCartSnapshotResponse> result =
				productInternalService.getCartSnapshots(List.of(PRODUCT_ID, productId2));

			assertThat(result).hasSize(2);
			then(sellerClient).should().getSellerInfo(SELLER_ID);
		}

		@Test
		@DisplayName("요청 id가 SUPERSEDED된 옛 row여도 family의 현재 ON_SALE row 데이터로 응답하고 productId는 요청 id를 유지한다")
		void getCartSnapshots_resolvesSupersededIdToCurrentOnSale() {
			UUID oldId = PRODUCT_ID;
			UUID currentId = UUID.fromString("55555555-5555-5555-5555-555555555555");
			Product old = product(oldId, SELLER_ID, ProductStatus.SUPERSEDED);
			Product current = product(currentId, SELLER_ID, ProductStatus.ON_SALE);
			ReflectionTestUtils.setField(current, "parentId", oldId);

			given(productRepository.findAllByIdIn(List.of(oldId))).willReturn(List.of(old));
			given(productRepository.findAllByFamilyRootIds(List.of(oldId))).willReturn(List.of(old, current));
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "프롬프트상점", null, "ACTIVE"));

			List<ProductCartSnapshotResponse> result = productInternalService.getCartSnapshots(List.of(oldId));

			assertThat(result).hasSize(1);
			assertThat(result.get(0).productId()).isEqualTo(oldId);
			assertThat(result.get(0).productTitle()).isEqualTo("면접 답변 프롬프트");
		}
	}

	@Nested
	@DisplayName("리뷰 등록/수정")
	class UpsertReview {

		@Test
		@DisplayName("자식 row의 id로 요청해도 family root에 리뷰가 귀속된다")
		void upsertReview_attachesReviewToFamilyRoot() {
			UUID rootId = UUID.fromString("44444444-4444-4444-4444-444444444444");
			Product root = product(rootId, SELLER_ID, ProductStatus.SUPERSEDED);
			Product child = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			ReflectionTestUtils.setField(child, "parentId", rootId);

			given(productRepository.findById(PRODUCT_ID)).willReturn(java.util.Optional.of(child));
			given(productRepository.findById(rootId)).willReturn(java.util.Optional.of(root));
			given(reviewRepository.findByUserIdAndProductId(SELLER_ID, rootId)).willReturn(java.util.Optional.empty());

			productInternalService.upsertReview(SELLER_ID, PRODUCT_ID, 5);

			org.mockito.ArgumentCaptor<com.prompthub.product.domain.model.entity.Review> captor =
				org.mockito.ArgumentCaptor.forClass(com.prompthub.product.domain.model.entity.Review.class);
			then(reviewRepository).should().save(captor.capture());
			assertThat(captor.getValue().getProduct()).isEqualTo(root);
		}
	}

	private Product product(UUID id, UUID sellerId, ProductStatus status) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "sellerId", sellerId);
		ReflectionTestUtils.setField(product, "name", "면접 답변 프롬프트");
		ReflectionTestUtils.setField(product, "productType", ProductType.PROMPT);
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
