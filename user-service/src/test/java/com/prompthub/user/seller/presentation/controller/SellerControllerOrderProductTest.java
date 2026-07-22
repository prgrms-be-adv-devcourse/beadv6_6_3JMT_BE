package com.prompthub.user.seller.presentation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SellerControllerOrderProductTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SellerQueryUseCase sellerQueryUseCase;

    @Test
    void getOrderProductSellers_기존_조회_유스케이스로_판매자_이름을_반환한다() throws Exception {
        UUID foundSellerId = UUID.randomUUID();
        UUID missingSellerId = UUID.randomUUID();
        List<String> sellerIds = List.of(foundSellerId.toString(), missingSellerId.toString());
        given(sellerQueryUseCase.findSellers(sellerIds))
                .willReturn(List.of(new SellerInfoResult(
                        foundSellerId.toString(), "김철수", "https://cdn.example.com/profile.png", "ACTIVE")));

        mockMvc.perform(post("/api/v2/users/order-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sellerIds", sellerIds))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellers[0].sellerId").value(foundSellerId.toString()))
                .andExpect(jsonPath("$.data.sellers[0].sellerName").value("김철수"))
                .andExpect(jsonPath("$.data.sellers[0].profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.sellers[0].status").doesNotExist())
                .andExpect(jsonPath("$.data.sellers[1].sellerId").value(missingSellerId.toString()))
                .andExpect(jsonPath("$.data.sellers[1].sellerName").value(Matchers.nullValue()));

        then(sellerQueryUseCase).should().findSellers(sellerIds);
    }

    @Test
    void getOrderProductSellers_빈_목록이면_400_V001을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v2/users/order-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));

        then(sellerQueryUseCase).shouldHaveNoInteractions();
    }

    @Test
    void getOrderProductSellers_31개_요청이면_400_V001을_반환한다() throws Exception {
        List<String> sellerIds = IntStream.range(0, 31)
                .mapToObj(index -> UUID.randomUUID().toString())
                .toList();

        mockMvc.perform(post("/api/v2/users/order-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sellerIds", sellerIds))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));

        then(sellerQueryUseCase).shouldHaveNoInteractions();
    }
}
