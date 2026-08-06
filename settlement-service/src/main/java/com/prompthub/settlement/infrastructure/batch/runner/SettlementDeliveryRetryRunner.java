package com.prompthub.settlement.infrastructure.batch.runner;

import com.prompthub.settlement.application.usecase.delivery.SettlementDeliveryUseCase;
import com.prompthub.settlement.domain.model.delivery.SettlementDeliveryStatus;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "settlement.execution.mode",
        havingValue = "delivery-retry")
public class SettlementDeliveryRetryRunner
        implements ApplicationRunner, ExitCodeGenerator {

    private final SettlementDeliveryUseCase deliveryUseCase;
    private final UUID deliveryId;
    private int exitCode = 1;

    public SettlementDeliveryRetryRunner(
            SettlementDeliveryUseCase deliveryUseCase,
            @Value("${settlement.delivery.retry-id}") UUID deliveryId) {
        this.deliveryUseCase = deliveryUseCase;
        this.deliveryId = deliveryId;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            SettlementDeliveryStatus status = deliveryUseCase.retry(deliveryId);
            exitCode = status == SettlementDeliveryStatus.DELIVERY_FAILED ? 1 : 0;
            log.info(
                    "정산 수동 재전송 종료. settlementDeliveryId={}, status={}",
                    deliveryId,
                    status);
        } catch (Exception exception) {
            exitCode = 1;
            log.error(
                    "정산 수동 재전송 실패. settlementDeliveryId={}",
                    deliveryId,
                    exception);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
