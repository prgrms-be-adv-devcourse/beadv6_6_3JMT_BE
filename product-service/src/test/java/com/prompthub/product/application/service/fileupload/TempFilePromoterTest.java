package com.prompthub.product.application.service.fileupload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.exception.ProductException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class TempFilePromoterTest {

	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID OTHER_SELLER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ObjectStorageGateway objectStorage;

	private TempFilePromoter tempFilePromoter;

	@BeforeEach
	void setUp() {
		tempFilePromoter = new TempFilePromoter(objectStorage);
	}

	@Test
	@DisplayName("본인 temp key를 상품 영구 경로로 승격하고, 활성 트랜잭션이 없으면 temp 원본을 즉시 정리한다")
	void promote_ownTempKey_copiesAndCleansUpTempOriginal() {
		String tempKey = "products/temp/" + SELLER_ID + "/thumbnail/thumb.png";
		String expectedPermanentKey = "products/" + PRODUCT_ID + "/thumbnail/thumb.png";

		TempFilePromoter.PromotedFiles result =
			tempFilePromoter.promote(tempKey, null, null, PRODUCT_ID, SELLER_ID);

		assertThat(result.thumbnailKey()).isEqualTo(expectedPermanentKey);
		then(objectStorage).should().copy(tempKey, expectedPermanentKey);
		then(objectStorage).should().delete(tempKey);
	}

	@Test
	@DisplayName("null key는 손대지 않고 그대로 반환한다")
	void promote_nullKeys_areNoOp() {
		TempFilePromoter.PromotedFiles result =
			tempFilePromoter.promote(null, null, null, PRODUCT_ID, SELLER_ID);

		assertThat(result.thumbnailKey()).isNull();
		assertThat(result.imageKeys()).isNull();
		assertThat(result.fileKey()).isNull();
		then(objectStorage).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("이미 영구 key인 값은 그대로 유지한다")
	void promote_permanentKeys_areUntouched() {
		String permanentThumbnail = "products/" + PRODUCT_ID + "/thumbnail/existing.png";
		String permanentFile = "products/" + PRODUCT_ID + "/file/existing.pptx";

		TempFilePromoter.PromotedFiles result =
			tempFilePromoter.promote(permanentThumbnail, List.of(), permanentFile, PRODUCT_ID, SELLER_ID);

		assertThat(result.thumbnailKey()).isEqualTo(permanentThumbnail);
		assertThat(result.fileKey()).isEqualTo(permanentFile);
		then(objectStorage).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("여러 소개 이미지 key의 순서를 유지한 채 승격한다")
	void promote_multipleImages_preservesOrder() {
		String temp1 = "products/temp/" + SELLER_ID + "/image/1.png";
		String permanent2 = "products/" + PRODUCT_ID + "/image/2.png"; // 이미 영구 key
		String temp3 = "products/temp/" + SELLER_ID + "/image/3.png";

		TempFilePromoter.PromotedFiles result = tempFilePromoter.promote(
			null, List.of(temp1, permanent2, temp3), null, PRODUCT_ID, SELLER_ID);

		assertThat(result.imageKeys()).containsExactly(
			"products/" + PRODUCT_ID + "/image/1.png",
			permanent2,
			"products/" + PRODUCT_ID + "/image/3.png"
		);
	}

	@Test
	@DisplayName("purpose가 다른 key가 하나라도 있으면 copy 전에 요청 전체를 거부한다")
	void promote_wrongPurpose_rejectsBeforeCopy() {
		String thumbnailKey = "products/temp/" + SELLER_ID + "/file/wrong.pptx";
		String imageKey = "products/temp/" + SELLER_ID + "/image/image.png";

		assertThatThrownBy(() -> tempFilePromoter.promote(
			thumbnailKey, List.of(imageKey), null, PRODUCT_ID, SELLER_ID))
			.isInstanceOf(ProductException.class);

		then(objectStorage).shouldHaveNoInteractions();
	}

	@Nested
	@DisplayName("트랜잭션 완료 보상")
	class TransactionCompensation {

		@Test
		@DisplayName("commit 후에는 temp 원본만 삭제하고 영구 객체를 유지한다")
		void promote_commit_deletesOnlyTempOriginal() {
			String tempKey = "products/temp/" + SELLER_ID + "/thumbnail/thumb.png";
			String permanentKey = "products/" + PRODUCT_ID + "/thumbnail/thumb.png";
			TransactionSynchronizationManager.initSynchronization();
			try {
				tempFilePromoter.promote(tempKey, null, null, PRODUCT_ID, SELLER_ID);

				completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

				then(objectStorage).should().delete(tempKey);
				then(objectStorage).should(never()).delete(permanentKey);
			} finally {
				clearSynchronization();
			}
		}

		@Test
		@DisplayName("rollback 후에는 새 영구 객체만 삭제하고 temp 원본을 유지한다")
		void promote_rollback_deletesOnlyPermanentCopy() {
			String tempKey = "products/temp/" + SELLER_ID + "/thumbnail/thumb.png";
			String permanentKey = "products/" + PRODUCT_ID + "/thumbnail/thumb.png";
			TransactionSynchronizationManager.initSynchronization();
			try {
				tempFilePromoter.promote(tempKey, null, null, PRODUCT_ID, SELLER_ID);

				completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

				then(objectStorage).should().delete(permanentKey);
				then(objectStorage).should(never()).delete(tempKey);
			} finally {
				clearSynchronization();
			}
		}

		private void completeTransaction(int status) {
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(synchronization -> synchronization.afterCompletion(status));
		}

		private void clearSynchronization() {
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.clearSynchronization();
			}
		}
	}

	@Test
	@DisplayName("다른 seller 소유의 temp key는 승격을 거부하고 storage를 호출하지 않는다")
	void promote_foreignSellerTempKey_rejectsWithoutTouchingStorage() {
		String foreignTempKey = "products/temp/" + OTHER_SELLER_ID + "/thumbnail/thumb.png";

		assertThatThrownBy(() -> tempFilePromoter.promote(foreignTempKey, null, null, PRODUCT_ID, SELLER_ID))
			.isInstanceOf(ProductException.class);

		then(objectStorage).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("copy가 실패하면 delete를 호출하지 않는다")
	void promote_copyFails_neverCallsDelete() {
		String tempKey = "products/temp/" + SELLER_ID + "/thumbnail/thumb.png";
		willThrow(new RuntimeException("S3 unavailable"))
			.given(objectStorage).copy(eq(tempKey), any());

		assertThatThrownBy(() -> tempFilePromoter.promote(tempKey, null, null, PRODUCT_ID, SELLER_ID))
			.isInstanceOf(RuntimeException.class);

		then(objectStorage).should(never()).delete(any());
	}

	@Nested
	@DisplayName("부분 실패 보상")
	class PartialFailureCompensation {

		@Test
		@DisplayName("일부만 실패하면 이 호출에서 새로 만든 영구 객체만 보상 삭제하고, 성공한 temp 원본은 지우지 않는다")
		void promote_partialFailure_compensatesOnlyNewPermanentObjects() {
			String thumbnailTempKey = "products/temp/" + SELLER_ID + "/thumbnail/thumb.png";
			String fileTempKey = "products/temp/" + SELLER_ID + "/file/doc.pptx";
			String expectedThumbnailPermanent = "products/" + PRODUCT_ID + "/thumbnail/thumb.png";
			String expectedFilePermanent = "products/" + PRODUCT_ID + "/file/doc.pptx";

			// 썸네일 copy는 성공, 파일 copy는 실패.
			// lenient(): 이 stub과 매칭되지 않는 썸네일 copy() 호출까지 strict-stub이
			// PotentialStubbingProblem으로 막지 않도록 한다 — 같은 메서드를 인자별로 다르게
			// 다루는(하나는 stub, 하나는 기본 동작) 테스트에서 흔한 패턴이다.
			lenient().doThrow(new RuntimeException("S3 unavailable"))
				.when(objectStorage).copy(eq(fileTempKey), any());

			assertThatThrownBy(() -> tempFilePromoter.promote(
				thumbnailTempKey, null, fileTempKey, PRODUCT_ID, SELLER_ID))
				.isInstanceOf(RuntimeException.class);

			then(objectStorage).should().copy(thumbnailTempKey, expectedThumbnailPermanent);
			then(objectStorage).should().copy(fileTempKey, expectedFilePermanent);
			// 보상: 이번 호출에서 새로 만든 썸네일 영구 객체만 삭제
			then(objectStorage).should().delete(expectedThumbnailPermanent);
			// temp 원본은 Lifecycle을 위해 그대로 둔다 — 아무것도 정리되지 않는다
			then(objectStorage).should(never()).delete(thumbnailTempKey);
			then(objectStorage).should(never()).delete(fileTempKey);
			then(objectStorage).should(never()).delete(expectedFilePermanent);
		}
	}
}
