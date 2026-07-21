package com.prompthub.user.sellersettlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.service.SellerSettlementApplicationService;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementDetailResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SellerSettlementControllerTest {

    private static final UUID SELLER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SellerSettlementApplicationService useCase;

    @Test
    void 월별목록은_settlementMonth와_페이지를_전달한다() throws Exception {
        given(useCase.getMySettlements(any()))
                .willReturn(new SellerSettlementListResponse(List.of(), 0, 0, 10));

        mockMvc.perform(get("/api/v2/sellers/me/settlements")
                        .header("X-User-Id", SELLER_ID)
                        .param("settlementMonth", "2026-07")
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.size").value(10));

        ArgumentCaptor<SellerSettlementListQuery> queryCaptor =
                ArgumentCaptor.forClass(SellerSettlementListQuery.class);
        then(useCase).should().getMySettlements(queryCaptor.capture());
        assertThat(queryCaptor.getValue().sellerId()).isEqualTo(SELLER_ID);
        assertThat(queryCaptor.getValue().status())
                .isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(queryCaptor.getValue().settlementMonth())
                .isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    void 월별상세를_조회한다() throws Exception {
        given(useCase.getMySettlementMonth(SELLER_ID, YearMonth.of(2026, 7)))
                .willReturn(emptyDetail("2026-07"));

        mockMvc.perform(get("/api/v2/sellers/me/settlements/months/2026-07")
                        .header("X-User-Id", SELLER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementMonth").value("2026-07"));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101"})
    void 잘못된_page_size는_400이다(String parameter, String value) throws Exception {
        mockMvc.perform(get("/api/v2/sellers/me/settlements")
                        .header("X-User-Id", SELLER_ID)
                        .param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void 잘못된_월형식은_400이다() throws Exception {
        mockMvc.perform(get("/api/v2/sellers/me/settlements/months/2026-13")
                        .header("X-User-Id", SELLER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));
    }

    private static SellerSettlementDetailResponse emptyDetail(String settlementMonth) {
        return new SellerSettlementDetailResponse(
                settlementMonth, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), List.of());
    }
}
