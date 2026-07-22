package com.prompthub.ai.settlement.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.prompthub.ai.settlement.application.port.SellerSettlementQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

@DisplayName("판매자 정산 Tool schema")
class SettlementAnalysisToolsTest {

    @Test
    @DisplayName("모델에는 네 기간 조회 Tool만 노출하고 사용자·실행 식별자는 인자로 노출하지 않는다")
    void exposesOnlySafePeriodArguments() {
        SettlementAnalysisTools tools = new SettlementAnalysisTools(
                mock(SellerSettlementQuery.class),
                mock(ToolExecutionGuard.class),
                new SimpleMeterRegistry());

        Map<String, ToolCallback> callbacks = Arrays.stream(ToolCallbacks.from(tools))
                .collect(Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()));

        assertThat(callbacks).containsOnlyKeys(
                "get_settlement_summary",
                "compare_settlement_periods",
                "get_weekly_settlement_breakdown",
                "get_payout_status");
        assertSchema(callbacks.get("get_settlement_summary"), "periodType", "period");
        assertSchema(callbacks.get("compare_settlement_periods"),
                "periodType", "currentPeriod", "comparisonPeriod");
        assertSchema(callbacks.get("get_weekly_settlement_breakdown"), "month");
        assertSchema(callbacks.get("get_payout_status"), "settlementMonth");
    }

    private void assertSchema(ToolCallback callback, String... expectedArguments) {
        String schema = callback.getToolDefinition().inputSchema();
        assertThat(schema).contains(expectedArguments);
        assertThat(schema.toLowerCase())
                .doesNotContain("actor", "seller", "runid", "token", "role");
    }
}
