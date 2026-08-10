package com.prompthub.product.infra.external.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageAdapterTest {

	private static final String BUCKET = "test-bucket";

	@Mock
	private S3Client s3Client;

	@Mock
	private S3Presigner s3Presigner;

	@Mock
	private PresignedGetObjectRequest presignedGetObjectRequest;

	@Mock
	private PresignedPutObjectRequest presignedPutObjectRequest;

	private S3ObjectStorageAdapter adapter;

	@BeforeEach
	void setUp() {
		AwsS3Properties properties = new AwsS3Properties("ap-northeast-2", new AwsS3Properties.S3(BUCKET));
		adapter = new S3ObjectStorageAdapter(s3Client, s3Presigner, properties);
	}

	@Nested
	@DisplayName("presigned GET URL 발급")
	class CreatePresignedGetUrl {

		@Test
		@DisplayName("bucket·key·30분 만료로 presign 요청을 만든다")
		void createPresignedGetUrl_buildsRequestWithBucketKeyAndExpiration() throws MalformedURLException {
			given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
				.willReturn(presignedGetObjectRequest);
			given(presignedGetObjectRequest.url()).willReturn(new URL("https://s3/get-url"));

			String url = adapter.createPresignedGetUrl("products/1/thumb.png");

			assertThat(url).isEqualTo("https://s3/get-url");
			ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
			then(s3Presigner).should().presignGetObject(captor.capture());
			GetObjectPresignRequest captured = captor.getValue();
			assertThat(captured.getObjectRequest().bucket()).isEqualTo(BUCKET);
			assertThat(captured.getObjectRequest().key()).isEqualTo("products/1/thumb.png");
			assertThat(captured.signatureDuration()).isEqualTo(Duration.ofMinutes(30));
		}

		@Test
		@DisplayName("presign 실패는 S3_PRESIGN_FAILED로 변환한다")
		void createPresignedGetUrl_presignerThrows_wrapsAsProductException() {
			willThrow(new RuntimeException("presign broken")).given(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));

			assertThatThrownBy(() -> adapter.createPresignedGetUrl("products/1/thumb.png"))
				.isInstanceOf(ProductException.class)
				.satisfies(e -> assertThat(((ProductException) e).getErrorCode())
					.isEqualTo(ProductErrorCode.S3_PRESIGN_FAILED));
		}
	}

	@Nested
	@DisplayName("presigned PUT URL 발급")
	class CreatePresignedPutUrl {

		@Test
		@DisplayName("bucket·key·contentType·10분 만료로 presign 요청을 만든다")
		void createPresignedPutUrl_buildsRequestWithBucketKeyContentTypeAndExpiration() throws MalformedURLException {
			given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
				.willReturn(presignedPutObjectRequest);
			given(presignedPutObjectRequest.url()).willReturn(new URL("https://s3/put-url"));

			String url = adapter.createPresignedPutUrl("products/temp/1/thumbnail/uuid.png", "image/png");

			assertThat(url).isEqualTo("https://s3/put-url");
			ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
			then(s3Presigner).should().presignPutObject(captor.capture());
			PutObjectPresignRequest captured = captor.getValue();
			assertThat(captured.putObjectRequest().bucket()).isEqualTo(BUCKET);
			assertThat(captured.putObjectRequest().key()).isEqualTo("products/temp/1/thumbnail/uuid.png");
			assertThat(captured.putObjectRequest().contentType()).isEqualTo("image/png");
			assertThat(captured.signatureDuration()).isEqualTo(Duration.ofMinutes(10));
		}
	}

	@Nested
	@DisplayName("copy")
	class Copy {

		@Test
		@DisplayName("source/dest bucket·key로 S3 copyObject를 호출한다")
		void copy_callsS3CopyObjectWithBucketAndKeys() {
			adapter.copy("products/temp/1/file/a.pptx", "products/2/file/a.pptx");

			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			then(s3Client).should().copyObject(captor.capture());
			CopyObjectRequest captured = captor.getValue();
			assertThat(captured.sourceBucket()).isEqualTo(BUCKET);
			assertThat(captured.sourceKey()).isEqualTo("products/temp/1/file/a.pptx");
			assertThat(captured.destinationBucket()).isEqualTo(BUCKET);
			assertThat(captured.destinationKey()).isEqualTo("products/2/file/a.pptx");
		}

		@Test
		@DisplayName("copy 실패는 S3_COPY_FAILED 전용 코드로 변환한다(S3_PRESIGN_FAILED 재사용 금지)")
		void copy_s3Throws_wrapsAsS3CopyFailed() {
			willThrow(new RuntimeException("copy broken")).given(s3Client).copyObject(any(CopyObjectRequest.class));

			assertThatThrownBy(() -> adapter.copy("products/temp/1/file/a.pptx", "products/2/file/a.pptx"))
				.isInstanceOf(ProductException.class)
				.satisfies(e -> assertThat(((ProductException) e).getErrorCode())
					.isEqualTo(ProductErrorCode.S3_COPY_FAILED));
		}
	}

	@Nested
	@DisplayName("delete")
	class Delete {

		@Test
		@DisplayName("bucket·key로 S3 deleteObject를 호출한다")
		void delete_callsS3DeleteObjectWithBucketAndKey() {
			adapter.delete("products/temp/1/thumbnail/a.png");

			ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			then(s3Client).should().deleteObject(captor.capture());
			assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
			assertThat(captor.getValue().key()).isEqualTo("products/temp/1/thumbnail/a.png");
		}

		@Test
		@DisplayName("delete 실패는 예외를 전파하지 않고 로그만 남긴다")
		void delete_s3Throws_isSwallowedNotPropagated() {
			willThrow(new RuntimeException("delete broken")).given(s3Client).deleteObject(any(DeleteObjectRequest.class));

			assertThatCode(() -> adapter.delete("products/temp/1/thumbnail/a.png")).doesNotThrowAnyException();
		}
	}
}
