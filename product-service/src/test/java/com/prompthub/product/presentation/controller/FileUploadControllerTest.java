package com.prompthub.product.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.product.application.usecase.FileUploadUseCase;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.ProductExceptionHandler;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.UploadUrlResponse;
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
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private FileUploadUseCase fileUploadUseCase;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FileUploadController(fileUploadUseCase))
            .setControllerAdvice(new ProductExceptionHandler())
            .build();
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("POST /api/v2/products/uploads/presigned-urls")
    class CreateUploadUrl {

        @Test
        @DisplayName("유스케이스가 만든 tempObjectKey·presignedPutUrl·presignedGetUrl을 그대로 응답한다")
        void createUploadUrl_delegatesToUseCase() throws Exception {
            given(fileUploadUseCase.createUploadUrl(eq(SELLER_ID), any()))
                .willReturn(new UploadUrlResponse(
                    "products/temp/" + SELLER_ID + "/file/uuid.pptx", "https://put-url", "https://get-url"));

            mockMvc.perform(post("/api/v2/products/uploads/presigned-urls")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"purpose\":\"file\",\"fileName\":\"a.pptx\",\"productType\":\"PPT\"}")
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tempObjectKey").value("products/temp/" + SELLER_ID + "/file/uuid.pptx"))
                .andExpect(jsonPath("$.data.presignedPutUrl").value("https://put-url"))
                .andExpect(jsonPath("$.data.presignedGetUrl").value("https://get-url"));
        }

        @Test
        @DisplayName("유스케이스가 정책 위반(P008)을 던지면 400으로 응답한다")
        void createUploadUrl_policyViolation_returnsBadRequest() throws Exception {
            given(fileUploadUseCase.createUploadUrl(eq(SELLER_ID), any()))
                .willThrow(new ProductException(ProductErrorCode.INVALID_UPLOAD_FILE_TYPE));

            mockMvc.perform(post("/api/v2/products/uploads/presigned-urls")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"purpose\":\"file\",\"fileName\":\"a.xlsx\",\"productType\":\"PPT\"}")
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("purpose가 비어 있으면 유스케이스 호출 전에 400으로 거절한다")
        void createUploadUrl_blankPurpose_rejectedByValidation() throws Exception {
            mockMvc.perform(post("/api/v2/products/uploads/presigned-urls")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"purpose\":\"\",\"fileName\":\"a.pptx\"}")
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isBadRequest());

            then(fileUploadUseCase).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v2/products/images")
    class DeleteTempImages {

        @Test
        @DisplayName("요청 본문의 object key 목록을 그대로 유스케이스에 위임한다")
        void deleteTempImages_delegatesObjectKeysToUseCase() throws Exception {
            List<String> objectKeys = List.of("products/temp/" + SELLER_ID + "/thumbnail/uuid.png");

            mockMvc.perform(delete("/api/v2/products/images")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(objectKeys))
                    .header("X-User-Id", SELLER_ID.toString())
                    .header("X-User-Role", "SELLER"))
                .andExpect(status().isOk());

            then(fileUploadUseCase).should().deleteTempObjects(SELLER_ID, objectKeys);
        }
    }
}
