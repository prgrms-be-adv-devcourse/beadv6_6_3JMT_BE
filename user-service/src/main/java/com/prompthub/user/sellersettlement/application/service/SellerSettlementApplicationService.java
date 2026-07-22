package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEvent;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementUseCase;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementAccessDeniedException;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementNotFoundException;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyAggregate;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyKey;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyPage;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementQueryRepository.MonthlyStatusCount;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementDetailResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementStatusResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponse;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellerSettlementApplicationService implements SeedSellerSettlementUseCase, SellerSettlementUseCase {

    private final SellerSettlementRepository sellerSettlementRepository;
    private final SellerSettlementQueryRepository sellerSettlementQueryRepository;

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
        MonthlyPage page = sellerSettlementQueryRepository.findMonthlyPage(
                query.sellerId(), query.status(), query.settlementMonth(),
                query.page(), query.size());
        List<MonthlyKey> keys = page.content().stream()
                .map(MonthlyAggregate::key)
                .toList();
        List<MonthlyStatusCount> counts =
                sellerSettlementQueryRepository.findStatusCounts(keys);
        return SellerSettlementListResponse.from(page, counts, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public SellerSettlementDetailResponse getMySettlementMonth(
            UUID sellerId, YearMonth settlementMonth) {
        MonthlyAggregate aggregate = sellerSettlementQueryRepository
                .findMonthlyAggregate(sellerId, settlementMonth)
                .orElseThrow(SellerSettlementNotFoundException::new);
        List<MonthlyStatusCount> counts = sellerSettlementQueryRepository
                .findStatusCounts(List.of(aggregate.key()));
        List<SellerSettlement> weeklySettlements = sellerSettlementQueryRepository
                .findWeeklySettlements(sellerId, settlementMonth);
        return SellerSettlementDetailResponse.from(
                aggregate, counts, weeklySettlements);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerSettlementSummaryResponse getMySummary(UUID sellerId) {
        BigDecimal totalRevenueAmount = sellerSettlementRepository.sumTotalAmountBySeller(sellerId);
        BigDecimal totalSettlementAmount = sellerSettlementRepository.sumPaidSettlementAmountBySeller(sellerId);
        return SellerSettlementSummaryResponse.of(totalRevenueAmount, totalSettlementAmount);
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
