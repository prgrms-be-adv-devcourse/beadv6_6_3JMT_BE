package com.prompthub.product.application.service.fileupload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.presentation.dto.request.UploadUrlRequest;
import com.prompthub.product.presentation.dto.response.UploadUrlResponse;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {

	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private ObjectStorageGateway objectStorage;

	private FileUploadService fileUploadService;

	@BeforeEach
	void setUp() {
		fileUploadService = new FileUploadService(new FileUploadPolicy(), objectStorage);
	}

	@Nested
	@DisplayName("업로드 URL 발급")
	class CreateUploadUrl {

		@Test
		@DisplayName("seller가 포함된 temp key와 presignedPutUrl·presignedGetUrl을 함께 생성한다")
		void createUploadUrl_generatesSellerScopedTempKeyAndThreeValues() {
			given(objectStorage.createPresignedPutUrl(any(), eq("image/png"))).willReturn("https://put-url");
			given(objectStorage.createPresignedGetUrl(any())).willReturn("https://get-url");

			UploadUrlResponse response = fileUploadService.createUploadUrl(
				SELLER_ID, new UploadUrlRequest("thumbnail", "photo.png", null));

			assertThat(response.tempObjectKey()).startsWith("products/temp/" + SELLER_ID + "/thumbnail/");
			assertThat(response.tempObjectKey()).endsWith(".png");
			assertThat(response.presignedPutUrl()).isEqualTo("https://put-url");
			assertThat(response.presignedGetUrl()).isEqualTo("https://get-url");
		}

		@Test
		@DisplayName("정책 위반이면 storage를 호출하지 않고 예외를 던진다")
		void createUploadUrl_policyViolation_neverCallsStorage() {
			assertThatThrownBy(() -> fileUploadService.createUploadUrl(
				SELLER_ID, new UploadUrlRequest("file", "sample.xlsx", "PPT")))
				.isInstanceOf(ProductException.class);

			then(objectStorage).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("temp object 삭제")
	class DeleteTempObjects {

		@Test
		@DisplayName("본인 소유 temp key만 삭제한다")
		void deleteTempObjects_ownTempKeys_areDeleted() {
			String ownTempKey = "products/temp/" + SELLER_ID + "/thumbnail/a.png";

			fileUploadService.deleteTempObjects(SELLER_ID, List.of(ownTempKey));

			then(objectStorage).should().delete(ownTempKey);
		}

		@Test
		@DisplayName("다른 seller 소유의 temp key는 삭제하지 않는다")
		void deleteTempObjects_foreignTempKey_isSkipped() {
			String foreignTempKey = "products/temp/" + UUID.randomUUID() + "/thumbnail/a.png";

			fileUploadService.deleteTempObjects(SELLER_ID, List.of(foreignTempKey));

			then(objectStorage).should(org.mockito.Mockito.never()).delete(any());
		}

		@Test
		@DisplayName("영구 key는 삭제하지 않는다")
		void deleteTempObjects_permanentKey_isSkipped() {
			String permanentKey = "products/" + UUID.randomUUID() + "/thumbnail/a.png";

			fileUploadService.deleteTempObjects(SELLER_ID, List.of(permanentKey));

			then(objectStorage).should(org.mockito.Mockito.never()).delete(any());
		}

		@Test
		@DisplayName("null·blank key는 무시한다")
		void deleteTempObjects_nullOrBlank_areIgnored() {
			fileUploadService.deleteTempObjects(SELLER_ID, Arrays.asList(null, "  "));

			then(objectStorage).shouldHaveNoInteractions();
		}
	}
}
