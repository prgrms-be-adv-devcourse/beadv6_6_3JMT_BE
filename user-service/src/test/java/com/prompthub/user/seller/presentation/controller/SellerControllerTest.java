package com.prompthub.user.seller.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.user.global.config.SecurityConfig;
import com.prompthub.user.seller.application.dto.RegisterSellerResult;
import com.prompthub.user.seller.application.usecase.SellerUseCase;
import com.prompthub.user.seller.domain.exception.SellerAlreadyAppliedException;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SellerController.class)
@Import(SecurityConfig.class)
class SellerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SellerUseCase sellerUseCase;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private RegisterSellerResult pendingResult() {
        return new RegisterSellerResult(REQUEST_ID, SellerRegisterStatus.PENDING, LocalDateTime.now());
    }

    @Test
    void register_정상_신청_201_반환() throws Exception {
        given(sellerUseCase.register(any())).willReturn(pendingResult());

        mockMvc.perform(post("/api/v1/seller/register")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["마케팅", "코딩"],
                                  "introduction": "소개글",
                                  "portfolioUrl": "https://blog.example.com",
                                  "agreedToTerms": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellerRequestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.submittedAt").isNotEmpty());
    }

    @Test
    void register_categories_누락_400() throws Exception {
        mockMvc.perform(post("/api/v1/seller/register")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agreedToTerms": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_categories_최대_3개_초과_400() throws Exception {
        mockMvc.perform(post("/api/v1/seller/register")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["A", "B", "C", "D"],
                                  "agreedToTerms": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_agreedToTerms_false_400() throws Exception {
        mockMvc.perform(post("/api/v1/seller/register")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["마케팅"],
                                  "agreedToTerms": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_XUserId_헤더_누락_403() throws Exception {
        mockMvc.perform(post("/api/v1/seller/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["마케팅"],
                                  "agreedToTerms": true
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void register_이미_신청된_판매자_409_A005() throws Exception {
        given(sellerUseCase.register(any())).willThrow(new SellerAlreadyAppliedException());

        mockMvc.perform(post("/api/v1/seller/register")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["마케팅"],
                                  "agreedToTerms": true
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("A005"));
    }
}
