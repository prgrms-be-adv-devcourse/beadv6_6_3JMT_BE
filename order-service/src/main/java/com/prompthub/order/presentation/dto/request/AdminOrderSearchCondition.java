package com.prompthub.order.presentation.dto.request;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 주문 목록 조회 조건")
public record AdminOrderSearchCondition(
	@Schema(description = "주문 상태 필터. ALL, CREATED, COMPLETED, FAILED, PARTIAL_REFUNDED, ALL_REFUNDED", example = "ALL", defaultValue = "ALL")
	String orderStatus,
	@Schema(description = "페이지 번호. 1부터 시작하며 생략 시 1", example = "1", defaultValue = "1")
	Integer page,
	@Schema(description = "페이지 크기. 1 이상 100 이하이며 생략 시 20", example = "20", defaultValue = "20")
	Integer size
) {

	private static final String ALL = "ALL";
	private static final int DEFAULT_PAGE = 1;
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;

	public AdminOrderSearchCondition resolve() {
		OrderStatus resolvedOrderStatus = resolvedOrderStatus();
		return new AdminOrderSearchCondition(
			resolvedOrderStatus == null ? ALL : resolvedOrderStatus.name(),
			resolvePage(),
			resolveSize()
		);
	}

	public OrderStatus resolvedOrderStatus() {
		String status = resolveOrderStatusText();
		if (ALL.equals(status)) {
			return null;
		}

		try {
			return OrderStatus.valueOf(status);
		} catch (IllegalArgumentException exception) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}

	private String resolveOrderStatusText() {
		if (orderStatus == null || orderStatus.isBlank()) {
			return ALL;
		}
		return orderStatus.trim().toUpperCase();
	}

	private int resolvePage() {
		if (page == null) {
			return DEFAULT_PAGE;
		}
		if (page < 1) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
		return page;
	}

	private int resolveSize() {
		if (size == null) {
			return DEFAULT_SIZE;
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
		return size;
	}
}
