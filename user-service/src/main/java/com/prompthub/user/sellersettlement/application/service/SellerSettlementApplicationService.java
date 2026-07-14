package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.client.ProductStatsClient;
import com.prompthub.user.sellersettlement.application.dto.SellerProductStats;
import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEvent;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementUseCase;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementAccessDeniedException;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementNotFoundException;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementStatusResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponse;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerSettlementApplicationService implements SeedSellerSettlementUseCase, SellerSettlementUseCase {

    private final SellerSettlementRepository sellerSettlementRepository;
    private final ProductStatsClient productStatsClient;

    @Override
    @Transactional
    public void seed(SettlementCreatedEvent event) {
        if (sellerSettlementRepository.existsBySettlementId(event.settlementId())) {
            return;
        }
        SellerSettlement settlement = SellerSettlement.seed(
                event.settlementId(), event.sellerId(),
                event.periodStart(), event.periodEnd(), event.productCount(),
                event.totalAmount(), event.settlementTotalAmount(),
                event.feeTotalAmount(), event.refundAmount(), event.calculatedAt());
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
    @Transactional(readOnly = true)
    public SellerSettlementSummaryResponse getMySummary(UUID sellerId) {
        SellerProductStats stats = productStatsClient.getSellerProductStats(sellerId);
        BigDecimal totalRevenueAmount = sellerSettlementRepository.sumTotalAmountBySeller(sellerId);
        BigDecimal totalSettlementAmount = sellerSettlementRepository.sumPaidSettlementAmountBySeller(sellerId);
        return SellerSettlementSummaryResponse.of(stats, totalRevenueAmount, totalSettlementAmount);
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
