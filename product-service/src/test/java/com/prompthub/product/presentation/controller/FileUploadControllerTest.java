package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.ProductExceptionHandler;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String S3_URL = "https://3jmt-prompthub-bucket.s3.ap-northeast-2.amazonaws.com/products/images/test.png";

    private MockMvc mockMvc;

    @Mock
    private StorageClient storageClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FileUploadController(storageClient))
            .setControllerAdvice(new ProductExceptionHandler())
            .build();
    }

    @Nested
    @DisplayName("POST /api/v1/sellers/me/products/images")
    class UploadImage {

        @Test
        @DisplayName("이미지 업로드 성공 시 S3 URL을 반환한다")
        void uploadImage_success() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", MediaType.IMAGE_PNG_VALUE, "fake-image-bytes".getBytes()
            );
            given(storageClient.upload(any(), any(), eq("image/png"))).willReturn(S3_URL);

            mockMvc.perform(multipart("/api/v1/sellers/me/products/images")
                    .file(file)
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.url").value(S3_URL));
        }

        @Test
        @DisplayName("productId가 있으면 products/{id}/images/ 경로로 업로드한다")
        void uploadImage_withProductId() throws Exception {
            UUID productId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image-bytes".getBytes()
            );
            given(storageClient.upload(any(), any(), eq("image/jpeg"))).willReturn(S3_URL);

            mockMvc.perform(multipart("/api/v1/sellers/me/products/images")
                    .file(file)
                    .param("productId", productId.toString())
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(S3_URL));
        }

        @Test
        @DisplayName("S3 업로드 실패 시 500을 반환한다")
        void uploadImage_s3Failure() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", MediaType.IMAGE_PNG_VALUE, "fake-image-bytes".getBytes()
            );
            given(storageClient.upload(any(), any(), any()))
                .willThrow(new ProductException(ProductErrorCode.S3_PRESIGN_FAILED));

            mockMvc.perform(multipart("/api/v1/sellers/me/products/images")
                    .file(file)
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isInternalServerError());
        }
    }
}
