package com.prompthub.product.presentation.dto.response;

import java.util.List;

public record ProductCartSnapshotsResponse(
	List<ProductCartSnapshotResponse> products
) {
}
