package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListResult;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementQueryServiceTest {

    @Mock
    private SellerSettlementRepository sellerSettlementRepository;

    @InjectMocks
    private SellerSettlementApplicationService service;

    private SellerSettlement approvedRow(UUID sellerId) {
        SellerSettlement s = SellerSettlement.seed(
                UUID.randomUUID(), sellerId,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                2, new BigDecimal("320000.00"), new BigDecimal("260000.00"),
                new BigDecimal("48000.00"), new BigDecimal("0.00"), LocalDateTime.of(2026, 7, 1, 4, 0));
        s.approve();
        return s;
    }

    @Test
    void getMySettlements_페이지를_Result로_변환() {
        UUID sellerId = UUID.randomUUID();
        SellerSettlement row = approvedRow(sellerId);
        given(sellerSettlementRepository.findPageBySeller(sellerId, null, null, 0, 10))
                .willReturn(new SellerSettlementRepository.SellerSettlementPage(List.of(row), 1));

        SellerSettlementListResult result = service.getMySettlements(
                new SellerSettlementListQuery(sellerId, null, null, 0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        SellerSettlementListResult.Item item = result.items().get(0);
        assertThat(item.settlementId()).isEqualTo(row.getSettlementId());
        assertThat(item.status()).isEqualTo(SettlementDisplayStatus.APPROVED);
        assertThat(item.payoutAmount()).isEqualByComparingTo("260000.00");
        assertThat(item.canRequestPayout()).isTrue();
        assertThat(item.period()).isEqualTo(YearMonth.of(2026, 6));
    }
}
