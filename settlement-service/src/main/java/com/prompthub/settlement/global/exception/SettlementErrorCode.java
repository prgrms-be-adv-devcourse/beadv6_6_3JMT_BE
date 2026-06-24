package com.prompthub.settlement.global.exception;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {

	INVALID_INPUT_VALUE("S-003", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
	INTERNAL_SERVER_ERROR("S-004", "예상하지 못한 서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
	UNAUTHENTICATED("S-005", "인증 정보가 없습니다.", HttpStatus.UNAUTHORIZED),
	FORBIDDEN("S-006", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),

	SETTLEMENT_BATCH_NOT_FOUND("S-001", "정산 배치를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SETTLEMENT_JOB_EXECUTION_FAILED("S-002", "정산 배치 잡 실행에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
	SETTLEMENT_BATCH_INVALID_STATE("S-007", "정산 배치가 처리 중 상태가 아닙니다.", HttpStatus.CONFLICT),
	SETTLEMENT_JOB_NOT_FOUND("S-008", "정산 배치 잡 실행 이력을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	SETTLEMENT_SOURCE_LINE_ALREADY_SETTLED("S-009", "이미 정산에 포함된 소스 라인입니다.", HttpStatus.CONFLICT),
	SETTLEMENT_NOT_FOUND("S-010", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	// S-011, S-012: 정산 취소(cancel) 트랙 예약
	SETTLEMENT_INVALID_STATE("S-013", "현재 상태에서 변경할 수 없는 정산입니다.", HttpStatus.CONFLICT);

	private final String code;
	private final String message;
	private final HttpStatus status;
}
