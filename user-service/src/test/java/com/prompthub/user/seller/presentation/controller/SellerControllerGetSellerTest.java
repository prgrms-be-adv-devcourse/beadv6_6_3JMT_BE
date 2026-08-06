package com.prompthub.user.seller.presentation.controller;

import java.util.UUID;

import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SellerControllerGetSellerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SellerQueryUseCase sellerQueryUseCase;

    private static final UUID SELLER_ID = UUID.randomUUID();

    @Test
    void getSeller_정상_조회_200() throws Exception {
        given(sellerQueryUseCase.findSeller(SELLER_ID.toString()))
                .willReturn(new SellerInfoResult(SELLER_ID.toString(), "김철수", "https://cdn.example.com/p.png", "ACTIVE"));

        mockMvc.perform(get("/api/v2/sellers/product").param("sellerId", SELLER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellerName").value("김철수"))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://cdn.example.com/p.png"));
    }

    @Test
    void getSeller_profileImageUrl_미등록시_null() throws Exception {
        given(sellerQueryUseCase.findSeller(SELLER_ID.toString()))
                .willReturn(new SellerInfoResult(SELLER_ID.toString(), "김철수", "", "ACTIVE"));

        mockMvc.perform(get("/api/v2/sellers/product").param("sellerId", SELLER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(Matchers.nullValue()));
    }

    @Test
    void getSeller_sellerId_누락_400() throws Exception {
        mockMvc.perform(get("/api/v2/sellers/product"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void getSeller_sellerId_UUID_형식_아님_400() throws Exception {
        mockMvc.perform(get("/api/v2/sellers/product").param("sellerId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void getSeller_존재하지_않는_sellerId_404() throws Exception {
        given(sellerQueryUseCase.findSeller(SELLER_ID.toString()))
                .willThrow(new UserNotFoundException());

        mockMvc.perform(get("/api/v2/sellers/product").param("sellerId", SELLER_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A001"));
    }
}
