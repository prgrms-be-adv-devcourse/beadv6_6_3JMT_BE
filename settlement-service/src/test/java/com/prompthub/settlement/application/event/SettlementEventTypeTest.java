package com.prompthub.settlement.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SettlementEventTypeTest {

    @Test
    void settlementCreatedCodeUsesUpperSnakeName() {
        assertThat(SettlementEventType.SETTLEMENT_CREATED.code())
                .isEqualTo("SETTLEMENT_CREATED");
    }
}
