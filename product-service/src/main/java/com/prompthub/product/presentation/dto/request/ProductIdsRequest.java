package com.prompthub.product.presentation.dto.request;

import java.util.List;
import java.util.UUID;

public record ProductIdsRequest(List<UUID> productIds) {
}
