package com.prompthub.order.presentation.dto.request;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@Schema(description = "페이지 기반 주문/결제 목록 조회 조건")
public record PageRequestParams(
	@Schema(description = "페이지 번호. 1부터 시작하며 생략 시 1", example = "1", defaultValue = "1")
	Integer page,
	@Schema(description = "페이지 크기. 1 이상 100 이하이며 생략 시 20", example = "20", defaultValue = "20")
	Integer size,
	@Schema(description = "주문 상태 필터. 허용 값: CREATED, COMPLETED, FAILED, PARTIAL_REFUNDED, ALL_REFUNDED", example = "COMPLETED")
	OrderStatus status,
	@Schema(description = "조회 시작일. yyyy-MM-dd 형식", example = "2026-06-01")
	@DateTimeFormat(iso = DATE)
	LocalDate from,
	@Schema(description = "조회 종료일. yyyy-MM-dd 형식", example = "2026-06-30")
	@DateTimeFormat(iso = DATE)
	LocalDate to
) {

	private static final int DEFAULT_PAGE = 1;
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;

	public PageRequestParams resolve() {
		PageRequestParams request = new PageRequestParams(
			resolvePage(),
			resolveSize(),
			status,
			from,
			to
		);
		request.validateDateRange();

		return request;
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

	private void validateDateRange() {
		if (from != null && to != null && from.isAfter(to)) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
