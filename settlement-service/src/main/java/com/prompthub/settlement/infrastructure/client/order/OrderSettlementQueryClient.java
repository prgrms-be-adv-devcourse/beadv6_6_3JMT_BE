package com.prompthub.settlement.infrastructure.client.order;

import com.prompthub.settlement.application.dto.SettleableLine;
import com.prompthub.settlement.application.port.OrderSettlementQueryPort;
import com.prompthub.settlement.domain.model.enums.SettlementSourceLineType;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import com.prompthub.settlement.grpc.ordersettlement.OrderSettlementQueryServiceGrpc.OrderSettlementQueryServiceBlockingStub;
import com.prompthub.settlement.grpc.ordersettlement.SettleableLinesRequest;
import com.prompthub.settlement.grpc.ordersettlement.SettleableLinesResponse;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * order-service 의 OrderSettlementQueryService 를 블로킹 스텁으로 호출해
 * 정산 대상 라인을 조회하는 아웃바운드 어댑터(#260).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSettlementQueryClient implements OrderSettlementQueryPort {

    private final OrderSettlementQueryServiceBlockingStub orderSettlementQueryStub;

    @Override
    public List<SettleableLine> fetchSettleableLines(YearMonth period) {
        try {
            SettleableLinesResponse response = orderSettlementQueryStub.getSettleableLines(
                    SettleableLinesRequest.newBuilder().setPeriod(period.toString()).build());
            return response.getLinesList().stream()
                    .map(this::toSettleableLine)
                    .toList();
        } catch (StatusRuntimeException e) {
            // 정산 대상 조달 실패를 조용히 삼키면 배치가 0건 정산으로 오인 종료된다. 배치를 실패시켜 드러낸다.
            log.error("order 정산 대상 라인 gRPC 조회 실패. period={}", period, e);
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_SOURCE_QUERY_FAILED, e);
        }
    }

    private SettleableLine toSettleableLine(
            com.prompthub.settlement.grpc.ordersettlement.SettleableLine line) {
        return new SettleableLine(
                SettlementSourceLineType.valueOf(line.getLineType()),
                UUID.fromString(line.getOrderId()),
                UUID.fromString(line.getOrderProductId()),
                UUID.fromString(line.getSellerId()),
                BigDecimal.valueOf(line.getLineAmount()),
                LocalDateTime.parse(line.getOccurredAt()));
    }
}
