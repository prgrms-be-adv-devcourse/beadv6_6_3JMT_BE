package com.prompthub.product.application.service;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.service.fileupload.TempFilePromoter;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductSellerServiceTest {

	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductEventProducer productEventProducer;

	@Mock
	private ObjectStorageGateway objectStorage;

	private ProductSellerService productSellerService;

	@BeforeEach
	void setUp() {
		// TempFilePromoter는 실제 인스턴스를 쓴다 — null/영구 key는 objectStorage를 전혀 건드리지
		// 않으므로 대부분의 테스트는 stubbing 없이도 그대로 통과하고, 실제 temp 승격 경로만
		// 필요한 테스트에서 objectStorage.copy/delete를 stub·검증한다.
		productSellerService = new ProductSellerService(
			productRepository, productEventProducer, objectStorage, new TempFilePromoter(objectStorage));
	}

	@Nested
	@DisplayName("검수 제출")
	class SubmitForReview {

		@Test
		@DisplayName("썸네일/이미지를 presign해 PRODUCT_REVIEW_REQUESTED를 발행한다")
		void submitForReview_presignsImagesAndPublishesEvent() {
			Product product = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			ReflectionTestUtils.setField(product, "thumbnailUrl", "products/1/thumbnail/a.png");
			ReflectionTestUtils.setField(product, "imageUrls", List.of("products/1/image/b.png"));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(objectStorage.createPresignedGetUrl("products/1/thumbnail/a.png"))
				.willReturn("https://s3/presigned-thumb");
			given(objectStorage.createPresignedGetUrl("products/1/image/b.png"))
				.willReturn("https://s3/presigned-image");

			productSellerService.submitForReview(SELLER_ID, PRODUCT_ID);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			then(productEventProducer).should().publishReviewRequested(
				product, null, "https://s3/presigned-thumb", List.of("https://s3/presigned-image"));
		}

		@Test
		@DisplayName("썸네일이 없으면 presign 없이 null로 발행한다")
		void submitForReview_withoutThumbnail_publishesNullThumbnail() {
			Product product = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			productSellerService.submitForReview(SELLER_ID, PRODUCT_ID);

			then(productEventProducer).should().publishReviewRequested(product, null, null, List.of());
			then(objectStorage).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("같은 content_hash를 가진 다른 판매자 상품이 있으면 duplicateOfProductId를 함께 발행한다")
		void submitForReview_withDuplicate_publishesDuplicateOfProductId() {
			Product product = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			UUID originalProductId = UUID.randomUUID();
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findDuplicateOfProductId(PRODUCT_ID, product.getContentHash(), SELLER_ID))
				.willReturn(Optional.of(originalProductId));

			productSellerService.submitForReview(SELLER_ID, PRODUCT_ID);

			then(productEventProducer).should().publishReviewRequested(product, originalProductId, null, List.of());
		}
	}

	@Nested
	@DisplayName("상품 수정")
	class UpdateProduct {

		@Test
		@DisplayName("한 번도 ON_SALE된 적 없으면 in-place로 수정한다")
		void updateProduct_neverOnSale_updatesInPlace() {
			Product draft = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(draft));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(draft));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MINOR"));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue()).isSameAs(draft);
			assertThat(draft.getName()).isEqualTo("새 제목");
			then(productEventProducer).should(never()).publishReviewRequested(any(), any(), any(), any());
		}

		@Test
		@DisplayName("한 번도 ON_SALE된 적 없어도 MAJOR 수정이면 PENDING_REVIEW로 전환하고 검수 요청 이벤트를 발행한다")
		void updateProduct_neverOnSale_majorTransitionsToPendingReview_publishesReviewRequested() {
			Product draft = product(PRODUCT_ID, null, ProductStatus.DRAFT, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(draft));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(draft));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MAJOR"));

			assertThat(draft.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			then(productEventProducer).should().publishReviewRequested(draft, null, null, List.of());
		}

		@Test
		@DisplayName("ON_SALE 이후 MAJOR 수정은 새 PENDING_REVIEW row를 만들고 기존 ON_SALE은 그대로 두며 검수 요청 이벤트를 발행한다")
		void updateProduct_majorAfterOnSale_createsPendingReviewChild_keepsOnSaleUntouched() {
			UUID familyRootId = PRODUCT_ID;
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MAJOR"));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			Product saved = captor.getValue();
			assertThat(saved).isNotSameAs(onSale);
			assertThat(saved.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			assertThat(saved.getMajorVersion()).isEqualTo((short) 3);
			assertThat(saved.getParentId()).isEqualTo(familyRootId);
			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			assertThat(onSale.getName()).isEqualTo("제목");
			then(productEventProducer).should().publishReviewRequested(saved, null, null, List.of());
		}

		@Test
		@DisplayName("ON_SALE 이후 PATCH 수정은 새 ON_SALE row를 만들고 기존 row는 SUPERSEDED로 전환한다")
		void updateProduct_patchAfterOnSale_createsOnSaleChild_supersedesPrevious() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MINOR"));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should(org.mockito.Mockito.times(2)).save(captor.capture());
			List<Product> saved = captor.getAllValues();
			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
			assertThat(saved).anySatisfy(p -> {
				assertThat(p.getStatus()).isEqualTo(ProductStatus.ON_SALE);
				assertThat(p.getPatchVersion()).isEqualTo((short) 1);
			});
			then(productEventProducer).should().publishProductChanged(PRODUCT_ID);
			then(productEventProducer).should(never()).publishReviewRequested(any(), any(), any(), any());
		}

		@Test
		@DisplayName("판매 후 REJECTED된 row는 같은 row의 콘텐츠만 수정하고 새 row·이벤트를 만들지 않는다")
		void updateProduct_rejectedRow_updatesInPlace_withoutNewRowOrEvent() {
			Product rejected = product(PRODUCT_ID, null, ProductStatus.REJECTED, (short) 3, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(rejected));

			productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MAJOR"));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue()).isSameAs(rejected);
			assertThat(rejected.getName()).isEqualTo("새 제목");
			assertThat(rejected.getStatus()).isEqualTo(ProductStatus.REJECTED);
			assertThat(rejected.getMajorVersion()).isEqualTo((short) 3);
			then(productRepository).should(never()).findAllByFamilyRootIds(any());
			then(productEventProducer).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 PENDING_REVIEW인 MAJOR 변경이 있으면 재제출을 거부한다")
		void updateProduct_majorWhilePendingReviewExists_throws() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), PRODUCT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale, pending));

			assertThatThrownBy(() -> productSellerService.updateProduct(SELLER_ID, PRODUCT_ID, request("MAJOR")))
				.isInstanceOf(ProductException.class);
		}
	}

	@Nested
	@DisplayName("상품 생성 - 유형별 필드")
	class CreateProduct {

		@Test
		@DisplayName("NOTION 생성 시 external_url을 외부 링크 원문 그대로 저장한다")
		void createProduct_notion_savesExternalUrlRaw() {
			given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
				.willAnswer(inv -> inv.getArgument(0));

			productSellerService.createProduct(SELLER_ID,
				new ProductCreateRequest(
					"노션 상품", "NOTION", "model", "설명", 1000,
					null, null, "https://notion.so/my-template", null, List.of(), List.of()
				));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue().getExternalUrl()).isEqualTo("https://notion.so/my-template");
			assertThat(captor.getValue().getFileUrl()).isNull();
			then(objectStorage).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("PPT 생성 시 file_object_key 임시 키를 상품 경로로 이동해 키로 저장한다")
		void createProduct_ppt_movesFileKey() {
			given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
				.willAnswer(inv -> inv.getArgument(0));
			String tempKey = "products/temp/" + SELLER_ID + "/file/abc.pptx";

			productSellerService.createProduct(SELLER_ID,
				new ProductCreateRequest(
					"PPT 상품", "PPT", "model", "설명", 1000,
					null, tempKey, null, null, List.of(), List.of()
				));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue().getFileUrl()).startsWith("products/");
			assertThat(captor.getValue().getFileUrl()).endsWith("/file/abc.pptx");
			assertThat(captor.getValue().getExternalUrl()).isNull();
			then(objectStorage).should().copy(
				org.mockito.ArgumentMatchers.eq(tempKey),
				org.mockito.ArgumentMatchers.anyString());
		}

		@Test
		@DisplayName("다른 판매자 소유의 temp key를 보내면 승격을 거부하고 상품을 저장하지 않는다")
		void createProduct_foreignTempKey_isRejected() {
			UUID otherSellerId = UUID.randomUUID();
			String foreignTempKey = "products/temp/" + otherSellerId + "/file/abc.pptx";

			assertThatThrownBy(() -> productSellerService.createProduct(SELLER_ID,
				new ProductCreateRequest(
					"PPT 상품", "PPT", "model", "설명", 1000,
					null, foreignTempKey, null, null, List.of(), List.of()
				)))
				.isInstanceOf(ProductException.class);

			then(productRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("상품 유형과 필드가 맞지 않으면 파일 승격 전에 거부한다")
		void createProduct_typeMismatch_rejectsBeforeFilePromotion() {
			String tempFileKey = "products/temp/" + SELLER_ID + "/file/abc.pptx";

			assertThatThrownBy(() -> productSellerService.createProduct(SELLER_ID,
				new ProductCreateRequest(
					"프롬프트 상품", "PROMPT", "model", "설명", 1000,
					"prompt", tempFileKey, null, null, List.of(), List.of()
				)))
				.isInstanceOf(ProductException.class);

			then(objectStorage).shouldHaveNoInteractions();
			then(productRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("생성 시 PRODUCT_CHANGED 이벤트를 발행한다")
		void createProduct_publishesProductChangedEvent() {
			given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
				.willAnswer(inv -> inv.getArgument(0));

			productSellerService.createProduct(SELLER_ID,
				new ProductCreateRequest(
					"노션 상품", "NOTION", "model", "설명", 1000,
					null, null, "https://notion.so/my-template", null, List.of(), List.of()
				));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			then(productEventProducer).should().publishProductChanged(captor.getValue().getId());
		}
	}

	@Nested
	@DisplayName("내 상품 목록 조회")
	class GetMyProducts {

		@Test
		@DisplayName("같은 family의 여러 row 중 대표 row 1개만 반환한다")
		void getMyProducts_returnsOneRepresentativeRowPerFamily() {
			Product superseded = product(UUID.randomUUID(), null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
			UUID familyRootId = superseded.getId();
			Product onSale = product(UUID.randomUUID(), familyRootId, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), familyRootId, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(superseded, onSale, pending));

			List<com.prompthub.product.presentation.dto.response.SellerProductListItemResponse> result =
				productSellerService.getMyProducts(SELLER_ID);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).productId()).isEqualTo(pending.getId());
		}

		@Test
		@DisplayName("ON_SALE과 REJECTED가 함께 있으면 REJECTED를 대표로 반환한다")
		void getMyProducts_returnsRejectedAsRepresentative_whenOnSaleAndRejectedCoexist() {
			Product onSale = product(UUID.randomUUID(), null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			UUID familyRootId = onSale.getId();
			Product rejected = product(UUID.randomUUID(), familyRootId, ProductStatus.REJECTED, (short) 3, (short) 0);
			given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(onSale, rejected));

			List<com.prompthub.product.presentation.dto.response.SellerProductListItemResponse> result =
				productSellerService.getMyProducts(SELLER_ID);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).productId()).isEqualTo(rejected.getId());
		}

		@Test
		@DisplayName("STOPPED만 있는 family도 대표 row로 노출한다")
		void getMyProducts_stoppedOnlyFamily_returnsStoppedRow() {
			Product stopped = product(UUID.randomUUID(), null, ProductStatus.STOPPED, (short) 1, (short) 0);
			given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(stopped));

			List<com.prompthub.product.presentation.dto.response.SellerProductListItemResponse> result =
				productSellerService.getMyProducts(SELLER_ID);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).productId()).isEqualTo(stopped.getId());
		}

		@Test
		@DisplayName("family root 기준으로 배치 조회한 평균 리뷰 평점을 대표 row에 채운다")
		void getMyProducts_fillsAverageRatingFromBatchLookup() {
			Product onSale = product(UUID.randomUUID(), null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			Product nextVersion = product(UUID.randomUUID(), onSale.getId(), ProductStatus.ON_SALE, (short) 1, (short) 1);
			UUID familyRootId = onSale.getId();
			given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(onSale, nextVersion));
			given(productRepository.getAverageRatings(List.of(familyRootId))).willReturn(Map.of(familyRootId, 4.5));

			List<com.prompthub.product.presentation.dto.response.SellerProductListItemResponse> result =
				productSellerService.getMyProducts(SELLER_ID);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).averageRating()).isEqualTo(4.5);
		}

		@Test
		@DisplayName("리뷰가 없는 family는 배치 조회 결과에 없어도 0.0으로 채운다")
		void getMyProducts_familyWithoutReviews_defaultsToZero() {
			Product stopped = product(UUID.randomUUID(), null, ProductStatus.STOPPED, (short) 1, (short) 0);
			given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(stopped));
			given(productRepository.getAverageRatings(List.of(stopped.getId()))).willReturn(Map.of());

			List<com.prompthub.product.presentation.dto.response.SellerProductListItemResponse> result =
				productSellerService.getMyProducts(SELLER_ID);

			assertThat(result.get(0).averageRating()).isEqualTo(0.0);
		}
	}

	@Nested
	@DisplayName("내 상품 상세 조회")
	class GetMyProduct {

		@Test
		@DisplayName("PENDING_REVIEW 대표 row와 별도로 현재 라이브 ON_SALE 버전 정보를 함께 반환한다")
		void getMyProduct_includesLiveVersion_whenPendingReviewExistsAlongsideOnSale() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), PRODUCT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale, pending));

			com.prompthub.product.presentation.dto.response.SellerProductDetailResponse result =
				productSellerService.getMyProduct(SELLER_ID, PRODUCT_ID);

			assertThat(result.productId()).isEqualTo(pending.getId());
			assertThat(result.liveVersion()).isEqualTo("2.0");
			assertThat(result.versions()).hasSize(2);
		}

		@Test
		@DisplayName("2.0 ON_SALE + 3.0 REJECTED에서 대표는 3.0, liveVersion은 2.0이다")
		void getMyProduct_returnsRejectedAsRepresentative_withOnSaleAsLiveVersion() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product rejected = product(UUID.randomUUID(), PRODUCT_ID, ProductStatus.REJECTED, (short) 3, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale, rejected));

			com.prompthub.product.presentation.dto.response.SellerProductDetailResponse result =
				productSellerService.getMyProduct(SELLER_ID, PRODUCT_ID);

			assertThat(result.productId()).isEqualTo(rejected.getId());
			assertThat(result.liveVersion()).isEqualTo("2.0");
		}

		@Test
		@DisplayName("판매자 상세는 fileUrl을 presigned로, externalUrl을 원문으로, fileObjectKey를 원본 key로 반환한다")
		void getMyProduct_exposesTypeFieldsAndObjectKeys() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			ReflectionTestUtils.setField(onSale, "fileUrl", "products/1/file/a.pptx");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale));
			given(objectStorage.createPresignedGetUrl("products/1/file/a.pptx"))
				.willReturn("https://s3/presigned-file");

			com.prompthub.product.presentation.dto.response.SellerProductDetailResponse result =
				productSellerService.getMyProduct(SELLER_ID, PRODUCT_ID);

			assertThat(result.fileUrl()).isEqualTo("https://s3/presigned-file");
			assertThat(result.fileObjectKey()).isEqualTo("products/1/file/a.pptx");
			assertThat(result.externalUrl()).isNull();
		}

		@Test
		@DisplayName("family root 기준으로 조회한 평균 리뷰 평점을 반환한다")
		void getMyProduct_fillsAverageRating() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale));
			given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(3.5);

			com.prompthub.product.presentation.dto.response.SellerProductDetailResponse result =
				productSellerService.getMyProduct(SELLER_ID, PRODUCT_ID);

			assertThat(result.averageRating()).isEqualTo(3.5);
		}
	}

	@Nested
	@DisplayName("셀러 상품 수/판매수 조회")
	class GetProductCount {

		@Test
		@DisplayName("product_count는 family 수, sales_count는 셀러 판매수 합으로 반환한다")
		void getProductCount_returnsFamilyCountAndSalesSum() {
			given(productRepository.countFamiliesBySellerId(SELLER_ID)).willReturn(3L);
			given(productRepository.sumSalesCountBySellerId(SELLER_ID)).willReturn(1240L);

			var response = productSellerService.getProductCount(SELLER_ID);

			assertThat(response.sellerId()).isEqualTo(SELLER_ID);
			assertThat(response.productCount()).isEqualTo(3L);
			assertThat(response.salesCount()).isEqualTo(1240L);
		}
	}

	private ProductUpdateRequest request(String versionType) {
		return new ProductUpdateRequest(
			"새 제목", "PROMPT", "model2", "새 설명", 2000, "content2",
			null, null, null, List.of(), List.of(), "변경 사유", versionType
		);
	}

	private Product product(UUID id, UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(id, SELLER_ID, promptContent());
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
