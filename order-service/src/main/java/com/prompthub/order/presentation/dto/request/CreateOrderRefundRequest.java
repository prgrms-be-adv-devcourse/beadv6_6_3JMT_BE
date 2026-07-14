package com.prompthub.order.presentation.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateOrderRefundRequest(
    @JsonProperty("payment_id") @NotNull UUID paymentId,
    @JsonProperty("order_product_ids")
    @NotEmpty List<@NotNull UUID> orderProductIds
) {
    public CreateOrderRefundRequest {
        orderProductIds = orderProductIds == null ? null : List.copyOf(orderProductIds);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown property: " + name);
    }
}
