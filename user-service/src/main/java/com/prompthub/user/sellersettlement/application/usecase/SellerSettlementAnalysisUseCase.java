package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementComparisonResult;
import com.prompthub.user.sellersettlement.application.dto.WeeklySettlementBreakdownResult;
import java.time.YearMonth;
import java.util.UUID;

public interface SellerSettlementAnalysisUseCase {

    SettlementAnalysisResult getSummary(
            UUID actorId, SettlementAnalysisPeriodType type, String period);

    SettlementComparisonResult compare(
            UUID actorId,
            SettlementAnalysisPeriodType type,
            String currentPeriod,
            String comparisonPeriod);

    WeeklySettlementBreakdownResult getWeeklyBreakdown(UUID actorId, YearMonth month);

    PayoutStatusResult getPayoutStatus(UUID actorId, YearMonth month);
}
