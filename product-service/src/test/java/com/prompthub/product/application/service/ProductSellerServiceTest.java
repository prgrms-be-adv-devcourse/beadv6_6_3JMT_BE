package com.prompthub.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
	private SellerClient sellerClient;

	@Mock
	private ProductEventProducer productEventProducer;

	@Mock
	private StorageClient storageClient;

	@InjectMocks
	private ProductSellerService productSellerService;

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
		}

		@Test
		@DisplayName("ON_SALE 이후 MAJOR 수정은 새 PENDING_REVIEW row를 만들고 기존 ON_SALE은 그대로 둔다")
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
		@DisplayName("NOTION 생성 시 content_file_url을 외부 링크 원문 그대로 저장한다")
		void createProduct_notion_savesContentFileUrlRaw() {
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new com.prompthub.product.application.client.SellerInfo(
					SELLER_ID, "판매자", null, "ACTIVE"));
			given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
				.willAnswer(inv -> inv.getArgument(0));

			productSellerService.createProduct(SELLER_ID,
				new com.prompthub.product.presentation.dto.request.ProductCreateRequest(
					"노션 상품", "NOTION", "model", "설명", 1000,
					null, null, "https://notion.so/my-template", null, List.of(), List.of()
				));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue().getContentFileUrl()).isEqualTo("https://notion.so/my-template");
			assertThat(captor.getValue().getFileUrl()).isNull();
			then(storageClient).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("PPT 생성 시 file_url 임시 키를 상품 경로로 이동해 키로 저장한다")
		void createProduct_ppt_movesFileKey() {
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new com.prompthub.product.application.client.SellerInfo(
					SELLER_ID, "판매자", null, "ACTIVE"));
			given(productRepository.save(org.mockito.ArgumentMatchers.any(Product.class)))
				.willAnswer(inv -> inv.getArgument(0));

			productSellerService.createProduct(SELLER_ID,
				new com.prompthub.product.presentation.dto.request.ProductCreateRequest(
					"PPT 상품", "PPT", "model", "설명", 1000,
					null, "products/temp/file/abc.pptx", null, null, List.of(), List.of()
				));

			ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
			then(productRepository).should().save(captor.capture());
			assertThat(captor.getValue().getFileUrl()).startsWith("products/");
			assertThat(captor.getValue().getFileUrl()).endsWith("/file/abc.pptx");
			assertThat(captor.getValue().getContentFileUrl()).isNull();
			then(storageClient).should().copyObject(
				org.mockito.ArgumentMatchers.eq("products/temp/file/abc.pptx"),
				org.mockito.ArgumentMatchers.anyString());
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
		@DisplayName("STOPPED만 있는 family도 대표 row로 노출한다")
		void getMyProducts_stoppedOnlyFamily_returnsStoppedRow() {
			Product stopped = product(UUID.randomUUID(), null, ProductStatus.STOPPED, (short) 1, (short) 0);
			given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(stopped));

			List<com.prompthub.product.presentation.dto.response.SellerProductListItemResponse> result =
				productSellerService.getMyProducts(SELLER_ID);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).productId()).isEqualTo(stopped.getId());
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
		@DisplayName("판매자 상세는 fileUrl을 presigned로, contentFileUrl을 원문으로 반환한다")
		void getMyProduct_exposesTypeFields() {
			Product onSale = product(PRODUCT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			ReflectionTestUtils.setField(onSale, "fileUrl", "products/1/file/a.pptx");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(onSale));
			given(storageClient.generatePresignedDownloadUrl("products/1/file/a.pptx"))
				.willReturn("https://s3/presigned-file");

			com.prompthub.product.presentation.dto.response.SellerProductDetailResponse result =
				productSellerService.getMyProduct(SELLER_ID, PRODUCT_ID);

			assertThat(result.fileUrl()).isEqualTo("https://s3/presigned-file");
			assertThat(result.contentFileUrl()).isNull();
		}
	}

	private ProductUpdateRequest request(String versionType) {
		return new ProductUpdateRequest(
			"새 제목", "PROMPT", "model2", "새 설명", 2000, "content2",
			null, null, null, List.of(), List.of(), "변경 사유", versionType
		);
	}

	private Product product(UUID id, UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			id, SELLER_ID, ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", null, null, List.of()
		);
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
