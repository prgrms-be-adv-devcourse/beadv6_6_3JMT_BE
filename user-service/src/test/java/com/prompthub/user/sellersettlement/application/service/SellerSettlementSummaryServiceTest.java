package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.prompthub.user.sellersettlement.application.client.ProductStatsClient;
import com.prompthub.user.sellersettlement.application.dto.SellerProductStats;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementSummaryServiceTest {

    @Mock
    private SellerSettlementRepository sellerSettlementRepository;

    @Mock
    private ProductStatsClient productStatsClient;

    @InjectMocks
    private SellerSettlementApplicationService service;

    @Test
    @DisplayName("요약: product gRPC(상품수·판매건수)와 정산 합계(거래액·지급완료액)를 합쳐 요약을 만든다")
    void getMySummary_combinesProductStatsAndSettlementSums() {
        // given
        UUID sellerId = UUID.randomUUID();
        given(productStatsClient.getSellerProductStats(sellerId))
                .willReturn(new SellerProductStats(3, 1342L));
        given(sellerSettlementRepository.sumTotalAmountBySeller(sellerId))
                .willReturn(new BigDecimal("10449800"));
        given(sellerSettlementRepository.sumPaidSettlementAmountBySeller(sellerId))
                .willReturn(new BigDecimal("170000"));

        // when
        SellerSettlementSummaryResponse response = service.getMySummary(sellerId);

        // then
        assertThat(response.registeredPromptCount()).isEqualTo(3);
        assertThat(response.totalSalesCount()).isEqualTo(1342L);
        assertThat(response.totalRevenueAmount()).isEqualByComparingTo("10449800");
        assertThat(response.totalSettlementAmount()).isEqualByComparingTo("170000");
    }
}
