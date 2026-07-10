package com.prompthub.paymentservice.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.common.event.EventType;
import org.junit.jupiter.api.Test;

class PaymentEventTypeTest {

    @Test
    void code는_enum_상수명을_그대로_반환한다() {
        assertThat(PaymentEventType.PAYMENT_APPROVED.code()).isEqualTo("PAYMENT_APPROVED");
        assertThat(PaymentEventType.PAYMENT_REFUNDED.code()).isEqualTo("PAYMENT_REFUNDED");
        assertThat(PaymentEventType.PAYMENT_FAILED.code()).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void 공통_EventType_인터페이스를_구현한다() {
        assertThat(PaymentEventType.PAYMENT_APPROVED).isInstanceOf(EventType.class);
    }
}
