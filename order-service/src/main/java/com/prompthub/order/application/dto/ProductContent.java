package com.prompthub.order.application.dto;

import java.util.UUID;

public record ProductContent(
	UUID productId,
	String content
) {
}
