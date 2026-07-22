package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductGrpcServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private StorageClient storageClient;

	private ProductGrpcService productGrpcService;

	@BeforeEach
	void setUp() {
		productGrpcService = new ProductGrpcService(new ProductFamilyResolver(productRepository), storageClient);
	}

	@Nested
	@DisplayName("장바구니 스냅샷 조회")
	class GetCartSnapshots {

		@Test
		@DisplayName("ON_SALE 상품만 조회하여 판매자 닉네임 없이 반환한다")
		void getCartSnapshots_onSaleOnly() {
			Product product = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));

			List<ProductCartSnapshotResponse> result = productGrpcService.getCartSnapshots(List.of(PRODUCT_ID));

			assertThat(result).hasSize(1);
			ProductCartSnapshotResponse snapshot = result.get(0);
			assertThat(snapshot.productId()).isEqualTo(PRODUCT_ID);
			assertThat(snapshot.sellerId()).isEqualTo(SELLER_ID);
			assertThat(snapshot.sellerNickname()).isNull();
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

			List<ProductCartSnapshotResponse> result = productGrpcService.getCartSnapshots(List.of(PRODUCT_ID));

			assertThat(result).isEmpty();
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

			List<ProductCartSnapshotResponse> result = productGrpcService.getCartSnapshots(List.of(oldId));

			assertThat(result).hasSize(1);
			assertThat(result.get(0).productId()).isEqualTo(oldId);
			assertThat(result.get(0).productTitle()).isEqualTo("면접 답변 프롬프트");
		}
	}

	@Nested
	@DisplayName("구매자 산출물 조회 (유형별)")
	class GetProductContent {

		@Test
		@DisplayName("PROMPT는 content 본문을 반환한다")
		void getProductContent_prompt() {
			Product product = onSaleWithType(ProductType.PROMPT);
			ReflectionTestUtils.setField(product, "content", "프롬프트 본문");
			mockResolve(product);

			ProductContentResponse response = productGrpcService.getProductContent(PRODUCT_ID);

			assertThat(response.content()).isEqualTo("프롬프트 본문");
			then(storageClient).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("PPT는 file_url을 presigned 다운로드 URL로 변환해 반환한다")
		void getProductContent_ppt() {
			Product product = onSaleWithType(ProductType.PPT);
			ReflectionTestUtils.setField(product, "fileUrl", "products/1/file/a.pptx");
			mockResolve(product);
			given(storageClient.generatePresignedDownloadUrl("products/1/file/a.pptx"))
				.willReturn("https://s3/presigned-file");

			ProductContentResponse response = productGrpcService.getProductContent(PRODUCT_ID);

			assertThat(response.content()).isEqualTo("https://s3/presigned-file");
		}

		@Test
		@DisplayName("NOTION은 external_url 원문을 반환한다")
		void getProductContent_notion() {
			Product product = onSaleWithType(ProductType.NOTION);
			ReflectionTestUtils.setField(product, "externalUrl", "https://notion.so/t");
			mockResolve(product);

			ProductContentResponse response = productGrpcService.getProductContent(PRODUCT_ID);

			assertThat(response.content()).isEqualTo("https://notion.so/t");
			then(storageClient).shouldHaveNoInteractions();
		}

		private Product onSaleWithType(ProductType type) {
			Product product = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			ReflectionTestUtils.setField(product, "productType", type);
			return product;
		}

		private void mockResolve(Product product) {
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
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
