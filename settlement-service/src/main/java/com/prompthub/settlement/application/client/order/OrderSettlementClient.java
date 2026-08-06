package com.prompthub.settlement.application.client.order;

import com.prompthub.settlement.application.dto.source.SettleableLine;
import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import java.util.List;

/**
 * 정산 대상 라인을 order-service 에서 동기 조회하는 아웃바운드 포트(비영속 — #260).
 * 구현 어댑터는 infrastructure/grpc/client/order 에서 gRPC 로 order 서버를 호출한다.
 */
public interface OrderSettlementClient {

    /**
     * 정산 주차에 아직 정산되지 않은 결제/환불 라인을 전부 조회한다.
     */
    List<SettleableLine> fetchSettleableLines(SettlementPeriod period);
}
