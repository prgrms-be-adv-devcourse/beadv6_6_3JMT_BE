package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.RefundCommand;

/**
 * order-service가 발행하는 ORDER_REFUND_REQUESTED 이벤트로 트리거되는 환불 처리.
 * refundRequestId 단위로 dedup하며, PG 호출까지 단일 트랜잭션 안에서 동기로 수행한다.
 */
public interface RefundUseCase {
    void refund(RefundCommand command);
}
