package com.prompthub.order.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNumberGeneratorTest {

	@Test
	@DisplayName("컬럼 길이 제한 안에서 주문 번호를 생성한다")
	void generateCreatesOrderNumberWithinColumnLimit() {
		OrderNumberGenerator generator = new OrderNumberGenerator();

		String orderNumber = generator.generate();

		assertThat(orderNumber)
			.hasSize(25)
			.matches("^ORD\\d{14}[0-9A-F]{8}$");
	}
}
