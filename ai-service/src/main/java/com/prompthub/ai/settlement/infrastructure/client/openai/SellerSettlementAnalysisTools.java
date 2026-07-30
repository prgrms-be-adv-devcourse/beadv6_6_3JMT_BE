package com.prompthub.ai.settlement.infrastructure.client.openai;

import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery.DashboardSummaryResult;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery.PayoutStatusResult;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery.SettlementComparisonResult;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery.SettlementSummaryResult;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery.WeeklyBreakdownResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SellerSettlementAnalysisTools {

    private final SellerSettlementAnalysisQuery analysisQuery;
    private final ToolExecutionGuard executionGuard;
    private final MeterRegistry meterRegistry;

    public SellerSettlementAnalysisTools(
            SellerSettlementAnalysisQuery analysisQuery,
            ToolExecutionGuard executionGuard,
            MeterRegistry meterRegistry
    ) {
        this.analysisQuery = analysisQuery;
        this.executionGuard = executionGuard;
        this.meterRegistry = meterRegistry;
    }

    @Tool(
            name = "get_settlement_dashboard_summary",
            description = "판매자 정산 대시보드와 동일한 취소 제외 누적 매출액과 "
                    + "승인 이후 누적 정산금액을 조회합니다. "
                    + "대시보드, 누적, 전체 정산금액 질문에 사용합니다.")
    public DashboardSummaryResult getSettlementDashboardSummary(ToolContext context) {
        return execute("get_settlement_dashboard_summary", context,
                analysisQuery::getDashboardSummary);
    }

    @Tool(
            name = "get_settlement_summary",
            description = "판매자 본인의 한 달 또는 완료된 한 주 정산 판매, 환불, 수수료와 "
                    + "승인 이후 정산만 포함한 지급액을 조회합니다. "
                    + "MONTH 지급액은 월별 정산 대시보드와 동일한 집계값입니다. "
                    + "MONTH period는 YYYY-MM, WEEK period는 월요일 YYYY-MM-DD입니다.")
    public SettlementSummaryResult getSettlementSummary(
            @ToolParam(description = "MONTH 또는 WEEK") String periodType,
            @ToolParam(description = "MONTH는 YYYY-MM, WEEK는 월요일 YYYY-MM-DD") String period,
            ToolContext context
    ) {
        return execute("get_settlement_summary", context,
                actorId -> analysisQuery.getSummary(actorId, periodType, period));
    }

    @Tool(
            name = "compare_settlement_periods",
            description = "판매자 본인의 두 월 또는 두 완료 주 정산을 비교합니다. 차액과 증감률은 서버 계산값입니다.")
    public SettlementComparisonResult compareSettlementPeriods(
            @ToolParam(description = "MONTH 또는 WEEK") String periodType,
            @ToolParam(description = "비교 기준이 되는 현재 기간") String currentPeriod,
            @ToolParam(description = "비교할 이전 또는 상대 기간") String comparisonPeriod,
            ToolContext context
    ) {
        return execute("compare_settlement_periods", context,
                actorId -> analysisQuery.comparePeriods(actorId, periodType, currentPeriod, comparisonPeriod));
    }

    @Tool(
            name = "get_weekly_settlement_breakdown",
            description = "판매자 본인의 지정 월을 완료된 주 단위 정산으로 나누어 조회합니다. "
                    + "지급액은 승인 이후 정산만 포함하며 month는 YYYY-MM입니다.")
    public WeeklyBreakdownResult getWeeklySettlementBreakdown(
            @ToolParam(description = "조회 월 YYYY-MM") String month,
            ToolContext context
    ) {
        return execute("get_weekly_settlement_breakdown", context,
                actorId -> analysisQuery.getWeeklyBreakdown(actorId, month));
    }

    @Tool(
            name = "get_payout_status",
            description = "판매자 본인의 지정 정산 월에 포함되는 주간 정산별 지급 상태와 상태별 건수를 조회합니다.")
    public PayoutStatusResult getPayoutStatus(
            @ToolParam(description = "정산 월 YYYY-MM") String settlementMonth,
            ToolContext context
    ) {
        return execute("get_payout_status", context,
                actorId -> analysisQuery.getPayoutStatus(actorId, settlementMonth));
    }

    private <T> T execute(
            String toolName,
            ToolContext context,
            Function<java.util.UUID, T> invocation
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        ToolExecutionGuard.RunningToolContext runningContext = null;
        try {
            runningContext = executionGuard.assertRunning(context);
            T result = invocation.apply(runningContext.actorId());
            executionGuard.assertRunning(context);
            meterRegistry.counter(
                    "ai.tools.calls",
                    "tool", toolName,
                    "outcome", outcome,
                    "error.code", "NONE")
                    .increment();
            return result;
        } catch (RuntimeException exception) {
            outcome = "failure";
            String errorCode = exception instanceof AiException aiException
                    ? aiException.getErrorCode().getCode()
                    : "AI_INTERNAL_ERROR";
            meterRegistry.counter(
                    "ai.tools.calls",
                    "tool", toolName,
                    "outcome", outcome,
                    "error.code", errorCode).increment();
            if (runningContext != null) {
                log.warn("AI settlement tool failed. runId={}, tool={}, errorCode={}",
                        runningContext.runId(), toolName, errorCode);
            }
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("ai.tools.duration", "tool", toolName, "outcome", outcome));
        }
    }
}
