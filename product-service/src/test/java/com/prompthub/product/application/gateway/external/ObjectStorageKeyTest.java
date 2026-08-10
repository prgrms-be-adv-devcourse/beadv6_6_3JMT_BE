package com.prompthub.product.application.gateway.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.application.service.fileupload.UploadPurpose;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ObjectStorageKeyTest {

	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Nested
	@DisplayName("생성")
	class Construction {

		@Test
		@DisplayName("정상 temp key는 그대로 보관한다")
		void of_validTempKey_succeeds() {
			ObjectStorageKey key = ObjectStorageKey.newTemp(SELLER_ID, UploadPurpose.THUMBNAIL, "png");

			assertThat(key.value()).startsWith("products/temp/" + SELLER_ID + "/thumbnail/");
			assertThat(key.value()).endsWith(".png");
			assertThat(key.isTemp()).isTrue();
		}

		@Test
		@DisplayName("null key는 거부한다")
		void of_null_throws() {
			assertThatThrownBy(() -> new ObjectStorageKey(null)).isInstanceOf(ProductException.class);
		}

		@Test
		@DisplayName("blank key는 거부한다")
		void of_blank_throws() {
			assertThatThrownBy(() -> new ObjectStorageKey("  ")).isInstanceOf(ProductException.class);
		}
	}

	@Nested
	@DisplayName("temp/영구 구분")
	class TempOrPermanent {

		@Test
		@DisplayName("products/temp/로 시작하지 않으면 영구 key로 본다")
		void isTemp_wrongPrefix_returnsFalse() {
			ObjectStorageKey key = new ObjectStorageKey("products/" + PRODUCT_ID + "/thumbnail/a.png");

			assertThat(key.isTemp()).isFalse();
			assertThat(key.isOwnedBy(SELLER_ID)).isFalse();
		}

		@Test
		@DisplayName("영구 key는 promote 호출 시 변경 없이 그대로 반환한다(no-op)")
		void promote_permanentKey_isNoOp() {
			ObjectStorageKey key = new ObjectStorageKey("products/" + PRODUCT_ID + "/thumbnail/a.png");

			ObjectStorageKey promoted = key.promote(PRODUCT_ID, SELLER_ID, UploadPurpose.THUMBNAIL);

			assertThat(promoted).isEqualTo(key);
		}
	}

	@Nested
	@DisplayName("소유권 검증")
	class Ownership {

		@Test
		@DisplayName("본인 seller의 temp key는 소유로 판정한다")
		void isOwnedBy_ownSeller_returnsTrue() {
			ObjectStorageKey key = ObjectStorageKey.newTemp(SELLER_ID, UploadPurpose.FILE, "pptx");

			assertThat(key.isOwnedBy(SELLER_ID)).isTrue();
		}

		@Test
		@DisplayName("다른 seller의 temp key는 소유가 아니다")
		void isOwnedBy_otherSeller_returnsFalse() {
			ObjectStorageKey key = ObjectStorageKey.newTemp(SELLER_ID, UploadPurpose.FILE, "pptx");
			UUID otherSeller = UUID.randomUUID();

			assertThat(key.isOwnedBy(otherSeller)).isFalse();
		}
	}

	@Nested
	@DisplayName("승격")
	class Promote {

		@Test
		@DisplayName("본인 temp key는 products/{productId}/{purpose}/{fileName}으로 승격한다")
		void promote_ownTempKey_succeeds() {
			ObjectStorageKey key = new ObjectStorageKey("products/temp/" + SELLER_ID + "/thumbnail/uuid.png");

			ObjectStorageKey promoted = key.promote(PRODUCT_ID, SELLER_ID, UploadPurpose.THUMBNAIL);

			assertThat(promoted.value()).isEqualTo("products/" + PRODUCT_ID + "/thumbnail/uuid.png");
		}

		@Test
		@DisplayName("다른 seller 소유의 temp key는 PRODUCT_FORBIDDEN으로 거부한다")
		void promote_foreignSeller_throwsForbidden() {
			UUID otherSeller = UUID.randomUUID();
			ObjectStorageKey key = new ObjectStorageKey("products/temp/" + otherSeller + "/thumbnail/uuid.png");

			assertThatThrownBy(() -> key.promote(PRODUCT_ID, SELLER_ID, UploadPurpose.THUMBNAIL))
				.isInstanceOf(ProductException.class)
				.satisfies(e -> assertThat(((ProductException) e).getErrorCode())
					.isEqualTo(ProductErrorCode.PRODUCT_FORBIDDEN));
		}

		@Test
		@DisplayName("세그먼트가 부족한 잘못된 prefix 형식은 거부한다")
		void promote_malformedSegments_throws() {
			assertThatThrownBy(() -> new ObjectStorageKey(
				"products/temp/" + SELLER_ID + "/thumbnail-only-two-segments"))
				.isInstanceOf(ProductException.class)
				.satisfies(e -> assertThat(((ProductException) e).getErrorCode())
					.isEqualTo(ProductErrorCode.INVALID_INPUT_VALUE));
		}

		@Test
		@DisplayName("잘못된 purpose 세그먼트는 거부한다")
		void promote_invalidPurposeSegment_throws() {
			assertThatThrownBy(() -> new ObjectStorageKey(
				"products/temp/" + SELLER_ID + "/not-a-purpose/uuid.png"))
				.isInstanceOf(ProductException.class);
		}

		@Test
		@DisplayName("썸네일 위치에 file purpose key를 사용하면 거부한다")
		void validateFor_wrongPurpose_throws() {
			ObjectStorageKey key = ObjectStorageKey.newTemp(SELLER_ID, UploadPurpose.FILE, "pptx");

			assertThatThrownBy(() -> key.validateFor(SELLER_ID, UploadPurpose.THUMBNAIL))
				.isInstanceOf(ProductException.class);
		}
	}

	@Test
	@DisplayName("object key 형식이 아닌 URL과 임의 문자열은 거부한다")
	void construction_nonObjectKeys_throw() {
		assertThatThrownBy(() -> new ObjectStorageKey("https://example.com/file.png"))
			.isInstanceOf(ProductException.class);
		assertThatThrownBy(() -> new ObjectStorageKey("garbage"))
			.isInstanceOf(ProductException.class);
	}
}
