package com.prompthub.order.application.service.order;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;

import java.util.List;
import java.util.function.ToIntFunction;

final class OrderAmountCalculator {

	private OrderAmountCalculator() {
	}

	static <T> int sum(List<T> items, ToIntFunction<T> amountExtractor) {
		if (items == null || items.isEmpty()) {
			throw invalidInput();
		}

		int totalAmount = 0;
		for (T item : items) {
			if (item == null) {
				throw invalidInput();
			}

			int itemAmount = amountExtractor.applyAsInt(item);
			if (itemAmount < 0) {
				throw invalidInput();
			}

			try {
				totalAmount = Math.addExact(totalAmount, itemAmount);
			} catch (ArithmeticException exception) {
				throw invalidInput();
			}
		}
		return totalAmount;
	}

	private static OrderException invalidInput() {
		return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
	}
}
