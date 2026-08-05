package com.prompthub.settlement.application.client.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SellerSettlementClientExceptionTest {

    @Test
    @DisplayName("Client 실패 정보는 기술 중립적인 code와 재시도 가능 여부를 보존한다")
    void failure_preservesCodeAndRetryable() {
        SellerSettlementClientFailure failure =
                new SellerSettlementClientFailure("UNAVAILABLE", true);

        assertThat(failure.code()).isEqualTo("UNAVAILABLE");
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    @DisplayName("Client 예외는 실패 정보와 원인 예외를 함께 보존한다")
    void exception_preservesFailureMessageAndCause() {
        SellerSettlementClientFailure failure =
                new SellerSettlementClientFailure("INVALID_ARGUMENT", false);
        IllegalStateException cause = new IllegalStateException("grpc failure");

        SellerSettlementClientException exception =
                new SellerSettlementClientException(failure, "gRPC INVALID_ARGUMENT", cause);

        assertThat(exception.getFailure()).isEqualTo(failure);
        assertThat(exception.getMessage()).isEqualTo("gRPC INVALID_ARGUMENT");
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getFailure().retryable()).isFalse();
    }
}
