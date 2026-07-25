package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.presentation.dto.response.PurchasedProductDetailResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PurchasedProductQueryServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ReviewRepository reviewRepository;

	@Mock
	private StorageClient storageClient;

	private PurchasedProductQueryService purchasedProductQueryService;

	@BeforeEach
	void setUp() {
		purchasedProductQueryService = new PurchasedProductQueryService(
			new ProductFamilyResolver(productRepository),
			productRepository,
			reviewRepository,
			storageClient
		);
	}

	@Test
	@DisplayName("PROMPT 상품은 content만 채워서 반환하고 평균/내 별점을 포함한다")
	void getPurchasedProduct_prompt_success() {
		Product product = onSaleProduct(ProductType.PROMPT);
		ReflectionTestUtils.setField(product, "content", "프롬프트 본문");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(4.5);
		Review review = review(product, (short) 5);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.of(review));

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.productId()).isEqualTo(PRODUCT_ID);
		assertThat(result.title()).isEqualTo("면접 답변 프롬프트");
		assertThat(result.productType()).isEqualTo("PROMPT");
		assertThat(result.model()).isEqualTo("GPT-4o");
		assertThat(result.content()).isEqualTo("프롬프트 본문");
		assertThat(result.fileUrl()).isNull();
		assertThat(result.externalUrl()).isNull();
		assertThat(result.sellerId()).isEqualTo(SELLER_ID);
		assertThat(result.averageRating()).isEqualTo(4.5);
		assertThat(result.myRating()).isEqualTo(5);
		verifyNoInteractions(storageClient);
	}

	@Test
	@DisplayName("PPT 상품은 fileUrl을 presigned URL로 채워서 반환한다")
	void getPurchasedProduct_ppt_presignedFileUrl() {
		Product product = onSaleProduct(ProductType.PPT);
		ReflectionTestUtils.setField(product, "fileUrl", "files/deck.pptx");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(0.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.empty());
		given(storageClient.generatePresignedDownloadUrl("files/deck.pptx")).willReturn("https://s3/presigned");

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.content()).isNull();
		assertThat(result.fileUrl()).isEqualTo("https://s3/presigned");
		assertThat(result.externalUrl()).isNull();
		assertThat(result.myRating()).isNull();
	}

	@Test
	@DisplayName("fileUrl이 비어 있으면 presign 없이 null을 반환한다")
	void getPurchasedProduct_blankFileUrl_returnsNull() {
		Product product = onSaleProduct(ProductType.EXCEL);
		ReflectionTestUtils.setField(product, "fileUrl", " ");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(0.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.empty());

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.fileUrl()).isNull();
		verifyNoInteractions(storageClient);
	}

	@Test
	@DisplayName("NOTION 상품은 externalUrl만 채워서 반환한다")
	void getPurchasedProduct_notion_externalUrl() {
		Product product = onSaleProduct(ProductType.NOTION);
		ReflectionTestUtils.setField(product, "externalUrl", "https://notion.so/x");
		stubFamily(product);
		given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(0.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(Optional.empty());

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.content()).isNull();
		assertThat(result.fileUrl()).isNull();
		assertThat(result.externalUrl()).isEqualTo("https://notion.so/x");
	}

	@Test
	@DisplayName("family에 ON_SALE 대표가 없으면 PRODUCT_NOT_FOUND")
	void getPurchasedProduct_noOnSale_throwsNotFound() {
		Product product = product(ProductType.PROMPT, ProductStatus.STOPPED);
		stubFamily(product);

		assertThatThrownBy(() -> purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID))
			.isInstanceOf(ProductException.class);
	}

	@Test
	@DisplayName("별점 조회는 요청 id가 아니라 family root id 기준이다")
	void getPurchasedProduct_ratingAnchorsToFamilyRoot() {
		UUID rootId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		Product product = onSaleProduct(ProductType.PROMPT);
		ReflectionTestUtils.setField(product, "parentId", rootId);
		ReflectionTestUtils.setField(product, "content", "본문");
		given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
		given(productRepository.findAllByFamilyRootIds(List.of(rootId))).willReturn(List.of(product));
		given(productRepository.getAverageRating(rootId)).willReturn(3.0);
		given(reviewRepository.findByUserIdAndProductId(USER_ID, rootId)).willReturn(Optional.empty());

		PurchasedProductDetailResponse result =
			purchasedProductQueryService.getPurchasedProduct(USER_ID, PRODUCT_ID);

		assertThat(result.averageRating()).isEqualTo(3.0);
	}

	private void stubFamily(Product product) {
		given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
		given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
	}

	private Product onSaleProduct(ProductType productType) {
		return product(productType, ProductStatus.ON_SALE);
	}

	private Product product(ProductType productType, ProductStatus status) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		ReflectionTestUtils.setField(product, "sellerId", SELLER_ID);
		ReflectionTestUtils.setField(product, "name", "면접 답변 프롬프트");
		ReflectionTestUtils.setField(product, "model", "GPT-4o");
		ReflectionTestUtils.setField(product, "productType", productType);
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}

	private Review review(Product product, short rating) {
		Review review = instantiate(Review.class);
		ReflectionTestUtils.setField(review, "userId", USER_ID);
		ReflectionTestUtils.setField(review, "product", product);
		ReflectionTestUtils.setField(review, "rating", rating);
		return review;
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
