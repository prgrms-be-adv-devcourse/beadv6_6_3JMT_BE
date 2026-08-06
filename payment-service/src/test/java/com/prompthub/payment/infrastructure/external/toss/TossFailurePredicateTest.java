package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class TossFailurePredicateTest {

    private final TossFailurePredicate predicate = new TossFailurePredicate();

    @Test
    void 네트워크_타임아웃_예외는_실패로_판정한다() {
        ResourceAccessException exception = new ResourceAccessException("connect timed out");

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void Toss_5xx_서버_오류는_실패로_판정한다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PG_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Toss 서버 오류", null, null
        );

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void 우리_쪽_요청_오류는_실패로_판정하지_않는다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PG_INVALID_REQUEST, "INVALID_REQUEST", "잘못된 요청", null, null
        );

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void 정상적인_결제_거절은_실패로_판정하지_않는다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PAYMENT_FAILED, "EXCEED_MAX_DAILY_PAYMENT_COUNT", "한도 초과", null, null
        );

        assertThat(predicate.test(exception)).isFalse();
    }
}
