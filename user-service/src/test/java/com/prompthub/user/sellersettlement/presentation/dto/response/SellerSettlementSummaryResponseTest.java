package com.prompthub.user.sellersettlement.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("판매자 정산 요약 응답 계약")
class SellerSettlementSummaryResponseTest {

    @Test
    @DisplayName("정산 소유 금액 두 필드만 노출한다")
    void exposesOnlySettlementOwnedAmountFields() {
        assertThat(SellerSettlementSummaryResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("totalRevenueAmount", "totalSettlementAmount");
    }
}
