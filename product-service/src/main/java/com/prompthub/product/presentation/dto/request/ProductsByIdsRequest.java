package com.prompthub.product.presentation.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record ProductsByIdsRequest(
	@NotEmpty List<UUID> productIds
) {
}
