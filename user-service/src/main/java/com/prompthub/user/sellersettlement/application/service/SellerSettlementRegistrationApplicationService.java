package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerSettlementRegistrationApplicationService {

    private final SellerSettlementRepository repository;
    private final SellerSettlementRegistrationMatcher matcher;

    @Transactional
    public UUID register(RegisterSellerSettlementCommand command) {
        return repository.findByDeliveryRequestId(command.deliveryRequestId())
                .map(SellerSettlement::getSettlementId)
                .orElseGet(() -> registerBySettlementId(command));
    }

    private UUID registerBySettlementId(RegisterSellerSettlementCommand command) {
        return repository.findBySettlementId(command.settlementId())
                .map(existing -> handleExisting(command, existing))
                .orElseGet(() -> create(command));
    }

    private UUID handleExisting(
            RegisterSellerSettlementCommand command,
            SellerSettlement existing) {
        if (existing.getDeliveryRequestId() == null && matcher.matches(command, existing)) {
            existing.linkDeliveryRequestId(command.deliveryRequestId());
            command.details().forEach(expected -> existing.getDetails().stream()
                    .filter(actual -> actual.getSettlementDetailId()
                            .equals(expected.settlementDetailId()))
                    .findFirst()
                    .orElseThrow()
                    .linkSettlementSourceLineId(expected.settlementSourceLineId()));
            repository.save(existing);
        }
        return existing.getSettlementId();
    }

    private UUID create(RegisterSellerSettlementCommand command) {
        List<SellerSettlementDetail> details = command.details().stream()
                .map(detail -> SellerSettlementDetail.seed(
                        detail.settlementDetailId(),
                        detail.settlementSourceLineId(),
                        detail.orderProductId(),
                        detail.lineType(),
                        detail.lineAmount(),
                        detail.feeRate(),
                        detail.feeAmount(),
                        detail.lineSettlementAmount(),
                        detail.occurredAt()))
                .toList();
        SellerSettlement settlement = SellerSettlement.seedV2(
                command.settlementId(),
                command.sellerId(),
                command.periodStart(),
                command.periodEnd(),
                command.productCount(),
                command.grossSalesAmount(),
                command.settlementTotalAmount(),
                command.feeTotalAmount(),
                command.refundAmount(),
                command.calculatedAt(),
                details);
        settlement.linkDeliveryRequestId(command.deliveryRequestId());
        repository.save(settlement);
        return settlement.getSettlementId();
    }
}
