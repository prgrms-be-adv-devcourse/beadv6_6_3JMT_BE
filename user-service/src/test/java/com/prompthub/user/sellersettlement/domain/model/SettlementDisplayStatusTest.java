package com.prompthub.user.sellersettlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SettlementDisplayStatusTest {

    @Test
    void 값은_7개이고_라벨을_갖는다() {
        assertThat(SettlementDisplayStatus.values()).hasSize(7);
        assertThat(SettlementDisplayStatus.WAITING.getLabel()).isEqualTo("대기");
        assertThat(SettlementDisplayStatus.PAID.getLabel()).isEqualTo("지급 완료");
        assertThat(SettlementDisplayStatus.CANCELLED.getLabel()).isEqualTo("취소");
    }
}
