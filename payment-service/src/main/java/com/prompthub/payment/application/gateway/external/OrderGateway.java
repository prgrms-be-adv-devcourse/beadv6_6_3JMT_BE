package com.prompthub.payment.application.gateway.external;

import java.util.UUID;

/**
 * 주문 정보 조회 출력 경계(DIP). 이벤트로 스냅샷을 확보하지 못했을 때의 폴백 경로.
 * infrastructure 계층이 gRPC로 구현한다.
 */
public interface OrderGateway {
    OrderPaymentInfo getOrderPaymentInfo(UUID orderId);
}
