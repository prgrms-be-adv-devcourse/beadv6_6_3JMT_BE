package com.prompthub.admin.order.domain.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 상태: CREATED(생성), COMPLETED(결제 완료), FAILED(결제 실패), PARTIAL_REFUNDED(부분 환불), ALL_REFUNDED(전체 환불)")
public enum OrderStatus {
	CREATED,
	COMPLETED,
	FAILED,
	PARTIAL_REFUNDED,
	ALL_REFUNDED
}
