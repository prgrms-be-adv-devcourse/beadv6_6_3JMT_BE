package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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

    @InjectMocks
    private SellerSettlementApplicationService service;

    @Test
    @DisplayName("요약: 정산 저장소의 누적 거래액과 지급 완료액을 반환한다")
    void getMySummary_returnsSettlementOwnedAmountSums() {
        // given
        UUID sellerId = UUID.randomUUID();
        given(sellerSettlementRepository.sumTotalAmountBySeller(sellerId))
                .willReturn(new BigDecimal("10449800"));
        given(sellerSettlementRepository.sumPaidSettlementAmountBySeller(sellerId))
                .willReturn(new BigDecimal("170000"));

        // when
        SellerSettlementSummaryResponse response = service.getMySummary(sellerId);

        // then
        assertThat(response.totalRevenueAmount()).isEqualByComparingTo("10449800");
        assertThat(response.totalSettlementAmount()).isEqualByComparingTo("170000");
    }
}
