package com.prompthub.ai.global.exception;

import com.prompthub.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AiErrorCode implements ErrorCode {

    INVALID_CHAT_MESSAGE("질문은 1자 이상 2,000자 이하여야 합니다.", HttpStatus.BAD_REQUEST),
    AI_RUN_NOT_FOUND("AI 실행을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    RUN_IN_PROGRESS("이미 실행 중인 질문이 있습니다.", HttpStatus.CONFLICT),
    AI_CAPACITY_EXCEEDED("AI 요청이 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),
    AI_CHAT_DISABLED("AI 정산 서비스가 현재 비활성화되어 있습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    AI_STATE_UNAVAILABLE("AI 정산 상태를 처리할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    SETTLEMENT_DATA_UNAVAILABLE("정산 데이터를 조회할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    AI_PROVIDER_UNAVAILABLE("AI 답변을 생성할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    AI_RESPONSE_POLICY_VIOLATION("AI 답변을 생성할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    TOOL_LOOP_LIMIT_EXCEEDED("정확한 답변에 필요한 조회 횟수를 초과했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    RUN_TIMEOUT("AI 답변 생성 시간을 초과했습니다.", HttpStatus.GATEWAY_TIMEOUT),
    AI_INTERNAL_ERROR("예상하지 못한 AI 서비스 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus status;

    AiErrorCode(String message, HttpStatus status) {
        this.message = message;
        this.status = status;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }
}
