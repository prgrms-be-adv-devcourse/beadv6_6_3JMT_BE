package com.prompthub.product.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.exception.ProductExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PRESIGNED_URL = "https://3jmt-prompthub-bucket.s3.ap-northeast-2.amazonaws.com/products/temp/thumbnail/uuid.png?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=3600";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private StorageClient storageClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FileUploadController(storageClient))
            .setControllerAdvice(new ProductExceptionHandler())
            .build();
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("POST /api/v2/sellers/me/products/uploads")
    class CreateUploadUrl {

        @Test
        @DisplayName("PPT 파일은 pptx content-type으로 presigned PUT URL을 발급한다")
        void createUploadUrl_pptxFile() throws Exception {
            given(storageClient.generatePresignedUploadUrl(
                org.mockito.ArgumentMatchers.startsWith("products/temp/file/"),
                eq("application/vnd.openxmlformats-officedocument.presentationml.presentation")))
                .willReturn("https://put-url");
            given(storageClient.generatePresignedDownloadUrl(org.mockito.ArgumentMatchers.anyString()))
                .willReturn("https://get-url");

            mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"purpose\":\"file\",\"fileName\":\"a.pptx\",\"productType\":\"PPT\"}")
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://put-url"))
                .andExpect(jsonPath("$.data.fileUrl").value("https://get-url"));
        }

        @Test
        @DisplayName("이미지는 확장자에 맞는 content-type으로 발급한다")
        void createUploadUrl_image() throws Exception {
            given(storageClient.generatePresignedUploadUrl(
                org.mockito.ArgumentMatchers.startsWith("products/temp/thumbnail/"), eq("image/png")))
                .willReturn("https://put-url");
            given(storageClient.generatePresignedDownloadUrl(org.mockito.ArgumentMatchers.anyString()))
                .willReturn("https://get-url");

            mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"purpose\":\"thumbnail\",\"fileName\":\"t.png\"}")
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://put-url"));
        }

        @Test
        @DisplayName("productType과 확장자가 맞지 않으면 400")
        void createUploadUrl_extMismatch() throws Exception {
            mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"purpose\":\"file\",\"fileName\":\"a.xlsx\",\"productType\":\"PPT\"}")
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("purpose=file인데 productType이 없으면 400")
        void createUploadUrl_fileWithoutType() throws Exception {
            mockMvc.perform(post("/api/v2/sellers/me/products/uploads")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"purpose\":\"file\",\"fileName\":\"a.pptx\"}")
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v2/sellers/me/products/images")
    class DeleteTempImages {

        @Test
        @DisplayName("temp 경로 URL은 key를 추출해 S3에서 삭제한다")
        void deleteTempImages_success() throws Exception {
            List<String> urls = List.of(PRESIGNED_URL);

            mockMvc.perform(delete("/api/v2/sellers/me/products/images")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(urls))
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk());

            verify(storageClient).deleteObject("products/temp/thumbnail/uuid.png");
        }

        @Test
        @DisplayName("temp 경로가 아닌 URL은 삭제하지 않는다")
        void deleteTempImages_skipsNonTemp() throws Exception {
            List<String> urls = List.of(
                "https://3jmt-prompthub-bucket.s3.ap-northeast-2.amazonaws.com/products/some-id/thumbnail/uuid.png?X-Amz-Expires=3600"
            );

            mockMvc.perform(delete("/api/v2/sellers/me/products/images")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(urls))
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk());

            verify(storageClient, never()).deleteObject(any());
        }
    }
}
