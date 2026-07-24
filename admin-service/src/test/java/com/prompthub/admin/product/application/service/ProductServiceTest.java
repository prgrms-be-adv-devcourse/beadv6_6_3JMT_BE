package com.prompthub.admin.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.admin.order.entity.SellerNickname;
import com.prompthub.admin.order.repository.SellerNicknameRepository;
import com.prompthub.admin.product.application.dto.AdminProductListQuery;
import com.prompthub.admin.product.application.dto.AdminProductPageResult;
import com.prompthub.admin.product.domain.exception.ProductException;
import com.prompthub.admin.product.domain.model.ProductListFilter;
import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.model.enums.AmountType;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import com.prompthub.admin.product.domain.model.enums.ProductType;
import com.prompthub.admin.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID FAMILY_ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final Pageable PAGE_0_20 = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

	@Mock
	private ProductRepository productRepository;

	@Mock
	private SellerNicknameRepository sellerNicknameRepository;

	@InjectMocks
	private ProductService productAdminService;

	@Nested
	@DisplayName("상품 목록 조회")
	class ListProducts {

		@Test
		@DisplayName("판매자 닉네임을 함께 채워서 반환한다")
		void listProducts_fillsSellerNickname() {
			Product pending = product(FAMILY_ROOT_ID, null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
			given(productRepository.findProducts(
				new ProductListFilter(ProductStatus.PENDING_REVIEW, null, List.of()), PAGE_0_20))
				.willReturn(new PageImpl<>(List.of(pending), PAGE_0_20, 1));
			SellerNickname nickname = Mockito.mock(SellerNickname.class);
			given(nickname.getSellerId()).willReturn(SELLER_ID);
			given(nickname.getNickname()).willReturn("판매자A");
			given(sellerNicknameRepository.findAllById(List.of(SELLER_ID))).willReturn(List.of(nickname));

			AdminProductPageResult result = productAdminService.listProducts(
				new AdminProductListQuery(ProductStatus.PENDING_REVIEW, null, PAGE_0_20));

			assertThat(result.items()).hasSize(1);
			assertThat(result.items().get(0).sellerNickname()).isEqualTo("판매자A");
			assertThat(result.total()).isEqualTo(1L);
			assertThat(result.hasNext()).isFalse();
		}

		@Test
		@DisplayName("닉네임을 찾을 수 없으면 '알 수 없음'으로 채운다")
		void listProducts_unknownSeller_fallsBack() {
			Product pending = product(FAMILY_ROOT_ID, null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
			given(productRepository.findProducts(new ProductListFilter(null, null, List.of()), PAGE_0_20))
				.willReturn(new PageImpl<>(List.of(pending), PAGE_0_20, 1));
			given(sellerNicknameRepository.findAllById(List.of(SELLER_ID))).willReturn(List.of());

			AdminProductPageResult result = productAdminService.listProducts(
				new AdminProductListQuery(null, null, PAGE_0_20));

			assertThat(result.items().get(0).sellerNickname()).isEqualTo("알 수 없음");
		}

		@Test
		@DisplayName("keyword가 있으면 닉네임으로 sellerId를 먼저 찾아 리포지토리에 전달한다")
		void listProducts_keyword_resolvesSellerIdsFirst() {
			SellerNickname nickname = Mockito.mock(SellerNickname.class);
			given(nickname.getSellerId()).willReturn(SELLER_ID);
			given(sellerNicknameRepository.findByNicknameContainingIgnoreCase("판매자")).willReturn(List.of(nickname));
			ProductListFilter filter = new ProductListFilter(null, "판매자", List.of(SELLER_ID));
			given(productRepository.findProducts(filter, PAGE_0_20))
				.willReturn(new PageImpl<>(List.of(), PAGE_0_20, 0));

			AdminProductPageResult result = productAdminService.listProducts(
				new AdminProductListQuery(null, "  판매자  ", PAGE_0_20));

			assertThat(result.items()).isEmpty();
			then(productRepository).should().findProducts(filter, PAGE_0_20);
		}

		@Test
		@DisplayName("total이 페이지 범위를 넘으면 hasNext가 true다")
		void listProducts_hasNext_whenTotalExceedsPage() {
			given(productRepository.findProducts(new ProductListFilter(null, null, List.of()), PAGE_0_20))
				.willReturn(new PageImpl<>(List.of(), PAGE_0_20, 45L));

			AdminProductPageResult result = productAdminService.listProducts(
				new AdminProductListQuery(null, null, PAGE_0_20));

			assertThat(result.hasNext()).isTrue();
			assertThat(result.total()).isEqualTo(45L);
		}
	}

	@Nested
	@DisplayName("상품 승인")
	class ApproveProduct {

		@Test
		@DisplayName("기존 ON_SALE row가 있으면 SUPERSEDED로 전환하고 대상 row를 ON_SALE로 승인한다")
		void approveProduct_supersedesPreviousOnSale() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale, pending));

			productAdminService.approveProduct(pending.getId());

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
			assertThat(pending.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should().save(onSale);
			then(productRepository).should().save(pending);
		}

		@Test
		@DisplayName("기존 ON_SALE row가 없으면(최초 승인) supersede 없이 승인만 한다")
		void approveProduct_firstApproval_noSupersede() {
			Product pending = product(FAMILY_ROOT_ID, null, ProductStatus.PENDING_REVIEW, (short) 1, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(pending));

			productAdminService.approveProduct(pending.getId());

			assertThat(pending.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should(org.mockito.Mockito.times(1)).save(pending);
		}

		@Test
		@DisplayName("PENDING_REVIEW가 아닌 상품은 승인할 수 없다")
		void approveProduct_notPendingReview_throws() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(onSale));

			assertThatThrownBy(() -> productAdminService.approveProduct(FAMILY_ROOT_ID))
				.isInstanceOf(ProductException.class);
		}
	}

	@Nested
	@DisplayName("상품 반려")
	class RejectProduct {

		@Test
		@DisplayName("반려 대상 row만 변경되고 family의 다른 row는 그대로 유지된다")
		void rejectProduct_onlyTargetRowChanges_othersUntouched() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 2, (short) 0);
			Product pending = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
			given(productRepository.findById(pending.getId())).willReturn(Optional.of(pending));

			productAdminService.rejectProduct(pending.getId(), "콘텐츠 미흡");

			assertThat(pending.getStatus()).isEqualTo(ProductStatus.REJECTED);
			assertThat(pending.getRejectionReason()).isEqualTo("콘텐츠 미흡");
			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			then(productRepository).should(org.mockito.Mockito.never()).save(onSale);
		}
	}

	@Nested
	@DisplayName("검수 대기로 되돌리기")
	class RevertProductToPendingReview {

		@Test
		@DisplayName("ON_SALE row를 되돌리면 짝이었던 SUPERSEDED row를 ON_SALE로 복원한다")
		void revert_onSaleRow_restoresPairedSupersededRow() {
			Product superseded = product(FAMILY_ROOT_ID, null, ProductStatus.SUPERSEDED, (short) 2, (short) 0);
			Product onSale = product(UUID.randomUUID(), FAMILY_ROOT_ID, ProductStatus.ON_SALE, (short) 3, (short) 0);
			given(productRepository.findById(onSale.getId())).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(superseded, onSale));

			productAdminService.revertProductToPendingReview(onSale.getId());

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			assertThat(superseded.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		}

		@Test
		@DisplayName("짝이 없으면(최초 승인) 대상 row만 되돌린다")
		void revert_onSaleRow_noSupersededPair_onlyTargetChanges() {
			Product onSale = product(FAMILY_ROOT_ID, null, ProductStatus.ON_SALE, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));

			productAdminService.revertProductToPendingReview(FAMILY_ROOT_ID);

			assertThat(onSale.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		}

		@Test
		@DisplayName("REJECTED row를 되돌릴 때는 family 조회 없이 대상만 변경한다")
		void revert_rejectedRow_doesNotTouchFamily() {
			Product rejected = product(FAMILY_ROOT_ID, null, ProductStatus.REJECTED, (short) 1, (short) 0);
			given(productRepository.findById(FAMILY_ROOT_ID)).willReturn(Optional.of(rejected));

			productAdminService.revertProductToPendingReview(FAMILY_ROOT_ID);

			assertThat(rejected.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
			then(productRepository).should(org.mockito.Mockito.never()).findAllByFamilyRootIds(org.mockito.ArgumentMatchers.anyList());
		}
	}

	private Product product(UUID id, UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create();
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "sellerId", SELLER_ID);
		ReflectionTestUtils.setField(product, "productType", ProductType.PROMPT);
		ReflectionTestUtils.setField(product, "name", "제목");
		ReflectionTestUtils.setField(product, "description", "설명");
		ReflectionTestUtils.setField(product, "model", "model");
		ReflectionTestUtils.setField(product, "amountType", AmountType.PAID);
		ReflectionTestUtils.setField(product, "amount", 1000);
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
