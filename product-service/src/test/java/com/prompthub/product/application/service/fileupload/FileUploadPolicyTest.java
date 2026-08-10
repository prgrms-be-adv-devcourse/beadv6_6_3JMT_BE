package com.prompthub.product.application.service.fileupload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileUploadPolicyTest {

	private final FileUploadPolicy fileUploadPolicy = new FileUploadPolicy();

	@Nested
	@DisplayName("purpose=FILE")
	class FilePurpose {

		@ParameterizedTest(name = "{0} + {1} 확장자는 성공한다")
		@CsvSource({
			"PPT, pptx",
			"PPT, ppt",
			"EXCEL, xlsx",
			"EXCEL, xls"
		})
		@DisplayName("productType에 맞는 확장자는 성공한다")
		void resolve_matchingProductTypeAndExtension_succeeds(String productType, String extension) {
			FileUploadPolicy.UploadDetails uploadDetails =
				fileUploadPolicy.getUploadDetails(UploadPurpose.FILE, productType, "sample." + extension);

			assertThat(uploadDetails.extension()).isEqualTo(extension);
			assertThat(uploadDetails.contentType()).isNotBlank();
		}

		@Test
		@DisplayName("대문자 확장자는 Locale.ROOT로 정규화해 동일하게 처리한다")
		void resolve_upperCaseExtension_normalizes() {
			FileUploadPolicy.UploadDetails uploadDetails =
				fileUploadPolicy.getUploadDetails(UploadPurpose.FILE, "PPT", "SAMPLE.PPTX");

			assertThat(uploadDetails.extension()).isEqualTo("pptx");
		}

		@Test
		@DisplayName("productType과 확장자가 맞지 않으면 P008로 거절한다")
		void resolve_mismatchedExtension_throws() {
			assertThatThrownBy(() -> fileUploadPolicy.getUploadDetails(UploadPurpose.FILE, "PPT", "sample.xlsx"))
				.isInstanceOf(ProductException.class)
				.satisfies(e -> assertThat(((ProductException) e).getErrorCode())
					.isEqualTo(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE));
		}

		@Test
		@DisplayName("productType이 없으면 P008로 거절한다")
		void resolve_missingProductType_throws() {
			assertThatThrownBy(() -> fileUploadPolicy.getUploadDetails(UploadPurpose.FILE, null, "sample.pptx"))
				.isInstanceOf(ProductException.class);
		}

		@Test
		@DisplayName("지원하지 않는 productType이면 P008로 거절한다")
		void resolve_unsupportedProductType_throws() {
			assertThatThrownBy(() -> fileUploadPolicy.getUploadDetails(UploadPurpose.FILE, "PROMPT", "sample.pptx"))
				.isInstanceOf(ProductException.class);
		}
	}

	@Nested
	@DisplayName("purpose=THUMBNAIL/IMAGE")
	class ImagePurpose {

		@ParameterizedTest(name = "{0} 확장자는 성공한다")
		@CsvSource({"jpg", "jpeg", "png", "gif", "webp"})
		@DisplayName("허용된 이미지 확장자는 성공한다")
		void resolve_allowedImageExtension_succeeds(String extension) {
			FileUploadPolicy.UploadDetails uploadDetails =
				fileUploadPolicy.getUploadDetails(UploadPurpose.THUMBNAIL, null, "photo." + extension);

			assertThat(uploadDetails.extension()).isEqualTo(extension);
			assertThat(uploadDetails.contentType()).startsWith("image/");
		}

		@Test
		@DisplayName("IMAGE purpose도 productType 없이 동작한다")
		void resolve_imagePurpose_ignoresProductType() {
			FileUploadPolicy.UploadDetails uploadDetails =
				fileUploadPolicy.getUploadDetails(UploadPurpose.IMAGE, null, "photo.png");

			assertThat(uploadDetails.contentType()).isEqualTo("image/png");
		}

		@Test
		@DisplayName("지원하지 않는 이미지 확장자는 P008로 거절한다")
		void resolve_unsupportedImageExtension_throws() {
			assertThatThrownBy(() -> fileUploadPolicy.getUploadDetails(UploadPurpose.THUMBNAIL, null, "photo.bmp"))
				.isInstanceOf(ProductException.class);
		}
	}

	@Test
	@DisplayName("확장자가 없는 파일명은 JPG로 추정하지 않고 P008로 거절한다")
	void resolve_fileNameWithoutExtension_throws() {
		assertThatThrownBy(() -> fileUploadPolicy.getUploadDetails(UploadPurpose.THUMBNAIL, null, "no-extension"))
			.isInstanceOf(ProductException.class)
			.satisfies(e -> assertThat(((ProductException) e).getErrorCode())
				.isEqualTo(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE));
	}

	@Test
	@DisplayName("파일명이 null이면 P008로 거절한다")
	void resolve_nullFileName_throws() {
		assertThatThrownBy(() -> fileUploadPolicy.getUploadDetails(UploadPurpose.THUMBNAIL, null, null))
			.isInstanceOf(ProductException.class);
	}
}
