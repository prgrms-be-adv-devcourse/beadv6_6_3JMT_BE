package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV1;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV2;
import com.prompthub.user.sellersettlement.application.event.SettlementDetailEvent;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementUseCase;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementAccessDeniedException;
import com.prompthub.user.sellersettlement.domain.exception.SellerSettlementNotFoundException;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
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
    public void seed(SettlementCreatedEventV1 event) {
        if (sellerSettlementRepository.existsBySettlementId(event.settlementId())) {
            return;
        }
        SellerSettlement settlement = SellerSettlement.seedV1(
                event.settlementId(), event.sellerId(),
                event.periodStart(), event.periodEnd(), event.productCount(),
                event.totalAmount(), event.settlementTotalAmount(),
                event.feeTotalAmount(), event.refundAmount(), event.calculatedAt());
        sellerSettlementRepository.save(settlement);
    }

    @Override
    @Transactional
    public void seed(SettlementCreatedEventV2 event) {
        if (sellerSettlementRepository.existsBySettlementId(event.settlementId())) {
            return;
        }
        List<SellerSettlementDetail> details = event.details().stream()
                .map(this::toDetail)
                .toList();
        SellerSettlement settlement = SellerSettlement.seedV2(
                event.settlementId(), event.sellerId(),
                event.periodStart(), event.periodEnd(), event.productCount(),
                event.totalAmount(), event.settlementTotalAmount(),
                event.feeTotalAmount(), event.refundAmount(), event.calculatedAt(), details);
        sellerSettlementRepository.save(settlement);
    }

    private SellerSettlementDetail toDetail(SettlementDetailEvent detail) {
        return SellerSettlementDetail.seed(
                detail.settlementDetailId(), detail.orderProductId(),
                SellerSettlementLineType.valueOf(detail.lineType()),
                detail.lineAmount(), detail.feeRate(), detail.feeAmount(),
                detail.lineSettlementAmount(), detail.occurredAt());
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
