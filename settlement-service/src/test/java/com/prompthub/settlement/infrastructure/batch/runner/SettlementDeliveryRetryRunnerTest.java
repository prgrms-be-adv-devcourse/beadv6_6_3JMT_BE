package com.prompthub.settlement.infrastructure.batch.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.settlement.application.usecase.delivery.SettlementDeliveryUseCase;
import com.prompthub.settlement.domain.model.delivery.SettlementDeliveryStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SettlementDeliveryRetryRunnerTest {

    private static final UUID DELIVERY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000663");

    @Test
    @DisplayName("delivery-retry 모드에서 대상 Delivery 하나를 재전송한다")
    void retriesSingleDelivery() throws Exception {
        SettlementDeliveryUseCase service =
                Mockito.mock(SettlementDeliveryUseCase.class);
        given(service.retry(DELIVERY_ID))
                .willReturn(SettlementDeliveryStatus.RECONCILED);
        SettlementDeliveryRetryRunner runner =
                new SettlementDeliveryRetryRunner(service, DELIVERY_ID);

        runner.run(new DefaultApplicationArguments());

        then(service).should().retry(DELIVERY_ID);
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    @DisplayName("최종 전달 실패는 Job 실패 종료 코드로 반환한다")
    void returnsFailureExitCodeWhenDeliveryFails() throws Exception {
        SettlementDeliveryUseCase service =
                Mockito.mock(SettlementDeliveryUseCase.class);
        given(service.retry(DELIVERY_ID))
                .willReturn(SettlementDeliveryStatus.DELIVERY_FAILED);
        SettlementDeliveryRetryRunner runner =
                new SettlementDeliveryRetryRunner(service, DELIVERY_ID);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("delivery-retry 모드에서만 Runner Bean을 생성한다")
    void createsRunnerOnlyInDeliveryRetryMode() {
        new ApplicationContextRunner()
                .withBean(
                        SettlementDeliveryUseCase.class,
                        () -> Mockito.mock(SettlementDeliveryUseCase.class))
                .withUserConfiguration(SettlementDeliveryRetryRunner.class)
                .withPropertyValues(
                        "settlement.execution.mode=delivery-retry",
                        "settlement.delivery.retry-id=" + DELIVERY_ID)
                .run(context -> assertThat(context)
                        .hasSingleBean(SettlementDeliveryRetryRunner.class));
    }
}
