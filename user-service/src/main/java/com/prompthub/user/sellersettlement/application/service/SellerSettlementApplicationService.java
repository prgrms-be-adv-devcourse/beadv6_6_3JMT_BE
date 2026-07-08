package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedMessage;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementUseCase;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementAccessDeniedException;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementNotFoundException;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementStatusResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerSettlementApplicationService implements SeedSellerSettlementUseCase, SellerSettlementUseCase {

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

    @Override
    @Transactional(readOnly = true)
    public SellerSettlementListResponse getMySettlements(SellerSettlementListQuery query) {
        SellerSettlementRepository.SellerSettlementPage page = sellerSettlementRepository.findPageBySeller(
                query.sellerId(), query.status(), query.period(), query.page(), query.size());
        return SellerSettlementListResponse.from(page, query.page(), query.size());
    }

    @Override
    @Transactional
    public SellerSettlementStatusResponse requestPayout(UUID sellerId, UUID settlementId) {
        SellerSettlement settlement = sellerSettlementRepository.findBySettlementId(settlementId)
                .orElseThrow(SellerSettlementNotFoundException::new);
        if (!settlement.getSellerId().equals(sellerId)) {
            throw new SellerSettlementAccessDeniedException();
        }
        settlement.requestPayout();
        sellerSettlementRepository.save(settlement);
        return SellerSettlementStatusResponse.from(settlement);
    }
}
