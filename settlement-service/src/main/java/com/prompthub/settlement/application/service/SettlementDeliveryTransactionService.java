package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SettlementDeliveryAttempt;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementDelivery;
import com.prompthub.settlement.domain.model.enums.SettlementDeliveryStatus;
import com.prompthub.settlement.domain.repository.SettlementDeliveryRepository;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementDeliveryTransactionService {

    private static final int MAX_ATTEMPTS = 3;
    private final SettlementDeliveryRepository deliveryRepository;
    private final SettlementRepository settlementRepository;

    @Transactional(readOnly = true)
    public List<UUID> findCalculatedIds(UUID batchId) {
        return deliveryRepository.findCalculatedByBatchId(batchId).stream()
                .map(SettlementDelivery::getId).toList();
    }

    @Transactional(readOnly = true)
    public Map<SettlementDeliveryStatus, Long> countByStatus(UUID batchId) {
        return deliveryRepository.countByStatus(batchId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SettlementDeliveryAttempt> beginAttempt(UUID deliveryId) {
        SettlementDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow();
        if (!delivery.canAttempt(MAX_ATTEMPTS)) {
            delivery.fail("gRPC 호출 결과 미확정: attempts=" + delivery.getAttemptCount());
            return Optional.empty();
        }
        delivery.recordAttempt(LocalDateTime.now());
        deliveryRepository.save(delivery);
        Settlement settlement = settlementRepository.findById(delivery.getSettlementId())
                .orElseThrow();
        SellerSettlementRegistrationCommand command = new SellerSettlementRegistrationCommand(
                delivery.getDeliveryRequestId(), settlement.getId(), settlement.getSellerId(),
                settlement.getPeriodStart(), settlement.getPeriodEnd(),
                settlement.getProductCount(), settlement.getTotalAmount(),
                settlement.getRefundAmount(), settlement.getFeeTotalAmount(),
                settlement.getSettlementTotalAmount(), settlement.getCalculatedAt(),
                settlement.getDetails().stream().map(detail ->
                        new SellerSettlementRegistrationCommand.Detail(
                                detail.getId(), detail.getSettlementSourceLineId(),
                                detail.getOrderProductId(), detail.getLineType(),
                                detail.getLineAmount(), detail.getFeeRate(), detail.getFeeAmount(),
                                detail.getLineSettlementAmount(), detail.getOccurredAt()))
                        .toList());
        return Optional.of(new SettlementDeliveryAttempt(
                delivery.getAttemptCount(), command));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReconciled(UUID deliveryId) {
        deliveryRepository.findById(deliveryId).orElseThrow()
                .reconcile(LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID deliveryId, String reason) {
        deliveryRepository.findById(deliveryId).orElseThrow().fail(reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markMismatch(UUID deliveryId, String reason) {
        deliveryRepository.findById(deliveryId).orElseThrow().mismatch(reason);
    }
}
