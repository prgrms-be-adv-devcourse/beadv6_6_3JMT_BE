package com.prompthub.user.seller.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.user.global.config.SecurityConfig;
import com.prompthub.user.seller.application.dto.RegisterSellerResult;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.seller.application.usecase.SellerUseCase;
import com.prompthub.user.seller.domain.exception.SellerAlreadyAppliedException;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SellerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SellerUseCase sellerUseCase;

    @MockitoBean
    private SellerQueryUseCase sellerQueryUseCase;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private RegisterSellerResult pendingResult() {
        return new RegisterSellerResult(REQUEST_ID, SellerRegisterStatus.PENDING, LocalDateTime.now());
    }

    @Test
    void register_정상_신청_201_반환() throws Exception {
        given(sellerUseCase.register(any())).willReturn(pendingResult());

        mockMvc.perform(post("/api/v2/seller/register")
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
        mockMvc.perform(post("/api/v2/seller/register")
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
        mockMvc.perform(post("/api/v2/seller/register")
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
        mockMvc.perform(post("/api/v2/seller/register")
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
        mockMvc.perform(post("/api/v2/seller/register")
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

        mockMvc.perform(post("/api/v2/seller/register")
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

    @Test
    void getWishlistSellers_기존_조회_유스케이스로_판매자_이름을_반환한다() throws Exception {
        UUID foundSellerId = UUID.randomUUID();
        UUID missingSellerId = UUID.randomUUID();
        given(sellerQueryUseCase.findSellers(
                List.of(foundSellerId.toString(), missingSellerId.toString())))
                .willReturn(List.of(new SellerInfoResult(
                        foundSellerId.toString(), "김철수", "", "ACTIVE")));

        mockMvc.perform(post("/api/v2/sellers/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("sellerIds", List.of(foundSellerId, missingSellerId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellers[0].sellerId").value(foundSellerId.toString()))
                .andExpect(jsonPath("$.data.sellers[0].sellerName").value("김철수"))
                .andExpect(jsonPath("$.data.sellers[1].sellerId").value(missingSellerId.toString()))
                .andExpect(jsonPath("$.data.sellers[1].sellerName")
                        .value(org.hamcrest.Matchers.nullValue()));

        then(sellerQueryUseCase).should().findSellers(
                List.of(foundSellerId.toString(), missingSellerId.toString()));
    }

    @Test
    void getWishlistSellers_빈_목록이면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v2/sellers/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));

        then(sellerQueryUseCase).shouldHaveNoInteractions();
    }

    @Test
    void getWishlistSellers_31개_요청이면_400을_반환한다() throws Exception {
        List<UUID> sellerIds = java.util.stream.IntStream.range(0, 31)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();

        mockMvc.perform(post("/api/v2/sellers/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sellerIds", sellerIds))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));

        then(sellerQueryUseCase).shouldHaveNoInteractions();
    }
}
