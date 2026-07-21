package com.prompthub.order.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record RefundOrderRequest(
		@Schema(description = "환불할 주문 상품 ID 목록")
		@NotEmpty List<@Valid @NotNull UUID> orderProductIds
	) {
		@AssertTrue(message = "orderProductIds must not contain duplicates")
		public boolean isOrderProductIdsUnique() {
			return orderProductIds == null || new HashSet<>(orderProductIds).size() == orderProductIds.size();
		}
	}