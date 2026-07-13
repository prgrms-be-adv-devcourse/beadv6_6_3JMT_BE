package com.prompthub.product.exception.enums;

import com.prompthub.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "V001", "입력값이 올바르지 않습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS001", "서버 내부 오류가 발생했습니다."),
	PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "상품이 존재하지 않습니다."),
	PRODUCT_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "P002", "판매 중인 상품이 아닙니다."),
	PRODUCT_FORBIDDEN(HttpStatus.FORBIDDEN, "P003", "본인의 상품만 수정/삭제할 수 있습니다."),
	INVALID_PRODUCT_TYPE(HttpStatus.BAD_REQUEST, "P004", "올바르지 않은 상품 유형입니다."),
	SELLER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "P005", "승인된 판매자만 상품을 등록할 수 있습니다."),
	PRODUCT_INVALID_STATUS(HttpStatus.CONFLICT, "P006", "현재 상태에서 처리할 수 없는 상품입니다."),
	PRODUCT_TYPE_FIELD_MISMATCH(HttpStatus.BAD_REQUEST, "P007", "상품 유형에 맞지 않는 필드 구성입니다."),
	S3_PRESIGN_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S001", "파일 업로드 URL 생성에 실패했습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
