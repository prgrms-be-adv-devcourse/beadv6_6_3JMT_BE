package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class TossRetryPredicateTest {

    private final TossRetryPredicate predicate = new TossRetryPredicate();

    @Test
    void 연결_거부_예외는_재시도_대상이다() {
        ResourceAccessException exception =
            new ResourceAccessException("Connection refused", new ConnectException("Connection refused"));

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void 순수_타임아웃은_재시도_대상이_아니다() {
        ResourceAccessException exception =
            new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void Toss_5xx_서버_오류는_재시도_대상이다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PG_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Toss 서버 오류", null, null
        );

        assertThat(predicate.test(exception)).isTrue();
    }

    @Test
    void 우리_쪽_요청_오류_4xx는_재시도_대상이_아니다() {
        PaymentGatewayException exception = new PaymentGatewayException(
            PaymentErrorCode.PAYMENT_FAILED, "EXCEED_MAX_DAILY_PAYMENT_COUNT", "한도 초과", null, null
        );

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void Bulkhead_포화_예외는_재시도_대상이_아니다() {
        BulkheadFullException exception = BulkheadFullException.createBulkheadFullException(
            io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test-bulkhead")
        );

        assertThat(predicate.test(exception)).isFalse();
    }

    @Test
    void RateLimiter_거절_예외는_재시도_대상이_아니다() {
        RequestNotPermitted exception = RequestNotPermitted.createRequestNotPermitted(
            io.github.resilience4j.ratelimiter.RateLimiter.ofDefaults("test-rate-limiter")
        );

        assertThat(predicate.test(exception)).isFalse();
    }
}
