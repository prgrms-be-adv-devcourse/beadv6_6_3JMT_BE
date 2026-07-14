package com.prompthub.paymentservice.application.usecase;

import com.prompthub.paymentservice.application.dto.command.ProcessRefundCommand;

/**
 * order-service가 발행하는 ORDER_REFUND_REQUESTED 이벤트로 트리거되는
 * OrderProduct 단위 부분환불 처리. PG 호출까지 단일 트랜잭션 안에서 동기로 수행한다.
 */
public interface ProcessRefundUseCase {
    void process(ProcessRefundCommand command);
}
