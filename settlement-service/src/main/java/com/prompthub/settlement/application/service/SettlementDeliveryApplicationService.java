package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SettlementDeliveryComparison;
import com.prompthub.settlement.application.dto.SettlementDeliverySummary;
import com.prompthub.settlement.application.exception.SellerSettlementDeliveryException;
import com.prompthub.settlement.application.port.SellerSettlementRegistrationPort;
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
    private final SellerSettlementRegistrationPort port;
    private final SettlementDeliveryReconciler reconciler;
    private final DeliveryRetrySleeper sleeper;

    public SettlementDeliverySummary deliverBatch(UUID batchId) {
        int reconciled = 0;
        int failed = 0;
        int mismatch = 0;
        var ids = transactions.findCalculatedIds(batchId);
        for (UUID id : ids) {
            Outcome outcome = deliver(id);
            reconciled += outcome == Outcome.RECONCILED ? 1 : 0;
            failed += outcome == Outcome.FAILED ? 1 : 0;
            mismatch += outcome == Outcome.MISMATCH ? 1 : 0;
        }
        SettlementDeliverySummary summary =
                new SettlementDeliverySummary(ids.size(), reconciled, failed, mismatch);
        log.info("정산 전달 배치 완료. batchId={}, total={}, reconciled={}, failed={}, mismatch={}",
                batchId, ids.size(), reconciled, failed, mismatch);
        return summary;
    }

    private Outcome deliver(UUID deliveryId) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            SellerSettlementRegistrationCommand command =
                    transactions.beginAttempt(deliveryId);
            try {
                var stored = port.register(command);
                SettlementDeliveryComparison comparison =
                        reconciler.compare(command, stored);
                if (comparison.matched()) {
                    transactions.markReconciled(deliveryId);
                    return Outcome.RECONCILED;
                }
                transactions.markMismatch(deliveryId, comparison.reason());
                return Outcome.MISMATCH;
            } catch (SellerSettlementDeliveryException exception) {
                if (!exception.isRetryable() || attempt == 3) {
                    transactions.markFailed(deliveryId,
                            "gRPC " + exception.getStatusCode() + ": attempts=" + attempt);
                    return Outcome.FAILED;
                }
                sleeper.sleep(BACKOFFS[attempt - 1]);
            }
        }
        throw new IllegalStateException("도달할 수 없는 정산 전달 상태입니다.");
    }

    private enum Outcome {
        RECONCILED, FAILED, MISMATCH
    }
}
