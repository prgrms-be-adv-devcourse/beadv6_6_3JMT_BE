package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;

public record SellerProductVersionResponse(
	String version,
	String status,
	String date,
	String changeReason,
	String rejectionReason
) {
	public static SellerProductVersionResponse from(Product product) {
		return new SellerProductVersionResponse(
			product.getMajorVersion() + "." + product.getPatchVersion(),
			product.getStatus().name(),
			product.getUpdatedAt().toLocalDate().toString(),
			product.getChangeReason(),
			product.getRejectionReason()
		);
	}
}
