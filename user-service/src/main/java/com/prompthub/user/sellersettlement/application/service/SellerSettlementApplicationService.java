package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.event.SettlementCreatedMessage;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerSettlementApplicationService implements SeedSellerSettlementUseCase {

    private final SellerSettlementRepository sellerSettlementRepository;

    @Override
    @Transactional
    public void seed(SettlementCreatedMessage message) {
        if (sellerSettlementRepository.existsBySettlementId(message.settlementId())) {
            return;
        }
        SellerSettlement settlement = SellerSettlement.seed(
                message.settlementId(), message.sellerId(),
                message.periodStart(), message.periodEnd(), message.productCount(),
                message.totalAmount(), message.settlementTotalAmount(),
                message.feeTotalAmount(), message.refundAmount(), message.calculatedAt());
        sellerSettlementRepository.save(settlement);
    }
}
