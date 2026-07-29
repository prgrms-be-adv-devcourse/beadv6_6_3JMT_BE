package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SettlementDeliveryComparison;
import com.prompthub.settlement.application.dto.SettlementDeliveryAttempt;
import com.prompthub.settlement.application.dto.SettlementDeliverySummary;
import com.prompthub.settlement.application.exception.SellerSettlementDeliveryException;
import com.prompthub.settlement.application.port.DeliveryRetrySleeper;
import com.prompthub.settlement.application.port.SellerSettlementRegistration;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementDeliveryApplicationService {

    private static final Duration[] BACKOFFS = {
            Duration.ofSeconds(1), Duration.ofSeconds(3)
    };
    private final SettlementDeliveryTransactionService transactions;
    private final SellerSettlementRegistration registration;
    private final SettlementDeliveryReconciler reconciler;
    private final DeliveryRetrySleeper sleeper;

    public SettlementDeliverySummary deliverBatch(UUID batchId) {
        var ids = transactions.findCalculatedIds(batchId);
        for (UUID id : ids) {
            deliver(id);
        }
        SettlementDeliverySummary summary =
                SettlementDeliverySummary.from(transactions.countByStatus(batchId));
        log.info(
                "정산 전달 배치 완료. batchId={}, total={}, calculated={}, "
                        + "reconciled={}, deliveryFailed={}, mismatch={}",
                batchId, summary.total(), summary.calculated(),
                summary.reconciled(), summary.deliveryFailed(), summary.mismatch());
        return summary;
    }

    private void deliver(UUID deliveryId) {
        while (true) {
            var prepared = transactions.beginAttempt(deliveryId);
            if (prepared.isEmpty()) {
                return;
            }
            SettlementDeliveryAttempt attempt = prepared.get();
            SellerSettlementRegistrationCommand command = attempt.command();
            long startedAt = System.nanoTime();
            try {
                var stored = registration.register(command);
                logAttempt(command, attempt.attemptNumber(), "OK", startedAt);
                SettlementDeliveryComparison comparison =
                        reconciler.compare(command, stored);
                if (comparison.matched()) {
                    transactions.markReconciled(deliveryId);
                    return;
                }
                transactions.markMismatch(deliveryId, comparison.reason());
                return;
            } catch (SellerSettlementDeliveryException exception) {
                logAttempt(command, attempt.attemptNumber(),
                        exception.getStatusCode().name(), startedAt);
                if (!exception.isRetryable() || attempt.attemptNumber() == 3) {
                    transactions.markFailed(deliveryId,
                            "gRPC " + exception.getStatusCode()
                                    + ": attempts=" + attempt.attemptNumber());
                    return;
                }
                sleeper.sleep(BACKOFFS[attempt.attemptNumber() - 1]);
            }
        }
    }

    private void logAttempt(
            SellerSettlementRegistrationCommand command,
            int attempt,
            String status,
            long startedAt) {
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.info(
                "정산 전달 호출 완료. settlementId={}, deliveryRequestId={}, "
                        + "attempt={}, grpcStatus={}, elapsedMs={}",
                command.settlementId(), command.deliveryRequestId(),
                attempt, status, elapsedMs);
    }
}
