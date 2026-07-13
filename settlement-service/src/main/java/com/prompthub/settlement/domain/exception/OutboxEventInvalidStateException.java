package com.prompthub.settlement.domain.exception;

import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;

public class OutboxEventInvalidStateException extends RuntimeException {

    public OutboxEventInvalidStateException(OutboxEventStatus current) {
        super("아웃박스 이벤트가 허용된 상태가 아닙니다. current=" + current);
    }
}
