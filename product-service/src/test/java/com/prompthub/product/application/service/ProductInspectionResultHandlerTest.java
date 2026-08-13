package com.prompthub.product.application.service;
import com.prompthub.product.application.service.inspection.ProductInspectionResultHandler;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductInspectionResultHandlerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final InspectionChecklist CHECKLIST =
		new InspectionChecklist(true, true, false, true, false, true, false);

	@Mock
	private ProductRepository productRepository;

	private ProductInspectionResultHandler handler;

	@Nested
	@DisplayName("승인/반려 반영")
	class Apply {

		@Test
		@DisplayName("approved=true면 PENDING_REVIEW 상품을 ON_SALE로 전이한다")
		void apply_approved_transitionsToOnSale() {
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(product.familyRootId())))
				.willReturn(List.of(product));

			handler.apply(PRODUCT_ID, true, null, CHECKLIST);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			assertThat(product.isHasContext()).isTrue();
			assertThat(product.isHasNuance()).isFalse();
		}

		@Test
		@DisplayName("승인되면 같은 가족에서 팔리던 이전 버전을 SUPERSEDED로 전환한다")
		void apply_approved_supersedesPreviousOnSaleVersion() {
			// #699 — 이 교대가 빠지면 메이저 승인마다 가족에 ON_SALE 행이 누적된다.
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			Product previous = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
			ReflectionTestUtils.setField(previous, "status", ProductStatus.ON_SALE);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(product.familyRootId())))
				.willReturn(List.of(previous, product));

			handler.apply(PRODUCT_ID, true, null, CHECKLIST);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
			assertThat(previous.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
		}

		@Test
		@DisplayName("approved=false면 사유와 함께 REJECTED로 전이한다")
		void apply_rejected_transitionsToRejectedWithReason() {
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			handler.apply(PRODUCT_ID, false, "금지 콘텐츠 포함", CHECKLIST);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.REJECTED);
			assertThat(product.getRejectionReason()).isEqualTo("금지 콘텐츠 포함");
			assertThat(product.isHasExecution()).isTrue();
		}

		@Test
		@DisplayName("이미 PENDING_REVIEW가 아니면(중복 이벤트) 예외를 던지지 않고 조용히 넘어간다")
		void apply_alreadyProcessed_doesNotThrow() {
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			handler.apply(PRODUCT_ID, true, null, CHECKLIST);

			assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		}

		@Test
		@DisplayName("상품이 없으면 조용히 넘어간다(삭제된 경우)")
		void apply_productNotFound_doesNotThrow() {
			handler = new ProductInspectionResultHandler(productRepository);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

			assertThatCode(() -> handler.apply(PRODUCT_ID, true, null, CHECKLIST)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("중복이 아닌 진짜 실패는 삼키지 않고 전파한다 — 예외를 제어 흐름으로 쓰지 않는다")
		void apply_realFailure_propagates() {
			handler = new ProductInspectionResultHandler(productRepository);
			Product product = pendingReviewProduct();
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(product.familyRootId())))
				.willReturn(List.of(product));
			given(productRepository.save(product)).willThrow(new RuntimeException("DB 오류"));

			assertThatThrownBy(() -> handler.apply(PRODUCT_ID, true, null, CHECKLIST))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("DB 오류");
		}
	}

	private Product pendingReviewProduct() {
		Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.PENDING_REVIEW);
		return product;
	}
}
