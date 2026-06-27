package com.prompthub.order.application.service.order;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class OrderNumberGenerator {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final SecureRandom RANDOM = new SecureRandom();

	public String generate() {
		return "ORD" + LocalDateTime.now().format(FORMATTER) + randomSuffix();
	}

	private String randomSuffix() {
		return String.format("%08X", RANDOM.nextInt());
	}
}
