package com.prompthub.admin.global.exception;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {

	INVALID_INPUT_VALUE("A-001", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
	INTERNAL_SERVER_ERROR("A-002", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
	SETTLEMENT_NOT_FOUND("A-003", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

	private final String code;
	private final String message;
	private final HttpStatus status;
}
