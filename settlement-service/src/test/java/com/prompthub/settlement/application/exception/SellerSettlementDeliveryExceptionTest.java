package com.prompthub.settlement.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SellerSettlementDeliveryExceptionTest {

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {
            "UNAVAILABLE", "DEADLINE_EXCEEDED"
    })
    void 일시적_gRPC_상태만_재시도한다(Status.Code code) {
        assertThat(SellerSettlementDeliveryException.from(code, "failure")
                .isRetryable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {
            "RESOURCE_EXHAUSTED", "UNAUTHENTICATED", "PERMISSION_DENIED",
            "INVALID_ARGUMENT", "FAILED_PRECONDITION"
    })
    void 영구적_gRPC_상태는_재시도하지_않는다(Status.Code code) {
        assertThat(SellerSettlementDeliveryException.from(code, "failure")
                .isRetryable()).isFalse();
    }
}
