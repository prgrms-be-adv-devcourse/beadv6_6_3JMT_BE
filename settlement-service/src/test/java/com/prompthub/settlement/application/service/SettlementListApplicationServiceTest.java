package com.prompthub.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDetail;
import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository;
import com.prompthub.settlement.domain.repository.SettlementListQueryRepository.SettlementPage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettlementListApplicationServiceTest {

    @Mock
    private SettlementListQueryRepository settlementListQueryRepository;

    @InjectMocks
    private SettlementListApplicationService settlementListApplicationService;

    private Settlement settlement(UUID sellerId) {
        SettlementDetail detail = SettlementDetail.sale(
                UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("0.15"),
                LocalDateTime.of(2026, 6, 15, 10, 0));
        return Settlement.create(UUID.randomUUID(), sellerId, YearMonth.of(2026, 6), List.of(detail));
    }

    @Test
    @DisplayName("조회한 정산을 응답 항목으로 매핑하고 페이징 정보를 조립한다")
    void getList_mapsSettlementsAndAssemblesPaging() {
        // given
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        when(settlementListQueryRepository.findPage(SettlementDisplayStatus.WAITING, 0, 20))
                .thenReturn(new SettlementPage(List.of(settlement(sellerA), settlement(sellerB)), 5L));

        // when
        SettlementListResult result = settlementListApplicationService.getList(
                new SettlementListQuery(SettlementDisplayStatus.WAITING, 0, 20));

        // then
        assertThat(result.totalElements()).isEqualTo(5L);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.items()).hasSize(2);

        SettlementListResult.Item first = result.items().get(0);
        assertThat(first.sellerId()).isEqualTo(sellerA);
        assertThat(first.productCount()).isEqualTo(1);
        assertThat(first.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(first.feeTotalAmount()).isEqualByComparingTo("15.00");
        assertThat(first.settlementTotalAmount()).isEqualByComparingTo("85.00");
        assertThat(first.displayStatus()).isEqualTo(SettlementDisplayStatus.WAITING);
        assertThat(result.items().get(1).sellerId()).isEqualTo(sellerB);
    }
}
