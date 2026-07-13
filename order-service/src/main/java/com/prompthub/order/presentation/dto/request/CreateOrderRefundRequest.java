package com.prompthub.order.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateOrderRefundRequest(
	@NotEmpty
	@Schema(description = "환불할 주문 상품 ID 목록")
	List<UUID> orderProductIds,
	@Size(max = 500)
	@Schema(description = "요청 전체의 공통 환불 사유", nullable = true)
	String reason
) {
	public CreateOrderRefundRequest {
		if (orderProductIds != null) {
			orderProductIds = List.copyOf(orderProductIds);
		}
	}
}
