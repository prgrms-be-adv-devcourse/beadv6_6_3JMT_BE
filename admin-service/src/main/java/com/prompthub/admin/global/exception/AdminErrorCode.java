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
	SETTLEMENT_NOT_FOUND("A-003", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SETTLEMENT_INVALID_STATE("A-004", "현재 상태에서 변경할 수 없는 정산입니다.", HttpStatus.CONFLICT),
	SETTLEMENT_ALREADY_PAID("A-005", "이미 지급 완료된 정산은 취소할 수 없습니다.", HttpStatus.CONFLICT),
	SETTLEMENT_ALREADY_CANCELLED("A-006", "이미 취소된 정산입니다.", HttpStatus.CONFLICT),
	USER_NOT_FOUND("A-007", "사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SELLER_REGISTER_NOT_FOUND("A-008", "판매자 등록 신청 내역이 없습니다.", HttpStatus.NOT_FOUND);

	private final String code;
	private final String message;
	private final HttpStatus status;
}
