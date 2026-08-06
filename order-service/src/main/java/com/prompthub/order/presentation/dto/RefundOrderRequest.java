package com.prompthub.order.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Schema(description = "주문 상품 부분 환불 요청")
public record RefundOrderRequest(
		@Schema(description = "환불할 주문 상품 ID 목록")
		@ArraySchema(
			minItems = 1,
			uniqueItems = true,
			schema = @Schema(description = "환불할 주문 상품 ID", format = "uuid")
		)
		@NotEmpty List<@Valid @NotNull UUID> orderProductIds
	) {
		@AssertTrue(message = "orderProductIds must not contain duplicates")
		@Schema(hidden = true)
		public boolean isOrderProductIdsUnique() {
			return orderProductIds == null || new HashSet<>(orderProductIds).size() == orderProductIds.size();
		}
	}
