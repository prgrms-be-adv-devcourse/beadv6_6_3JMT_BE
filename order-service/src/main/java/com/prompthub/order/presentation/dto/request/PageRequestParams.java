package com.prompthub.order.presentation.dto.request;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

public record PageRequestParams(
	Integer page,
	Integer size,
	OrderStatus status,
	@DateTimeFormat(iso = DATE)
	LocalDate from,
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
