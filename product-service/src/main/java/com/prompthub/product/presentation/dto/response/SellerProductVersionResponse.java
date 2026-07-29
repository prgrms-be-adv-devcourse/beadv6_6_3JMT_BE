package com.prompthub.product.presentation.dto.response;

import com.prompthub.product.domain.model.entity.Product;

public record SellerProductVersionResponse(
	String version,
	String status,
	String date,
	String changeReason,
	String rejectionReason,
	boolean hasContext,
	boolean hasObjective,
	boolean hasNuance,
	boolean hasTone,
	boolean hasExamples,
	boolean hasExecution,
	boolean hasRoleAssignment,
	boolean checklistRecorded
) {
	public static SellerProductVersionResponse from(Product product) {
		return new SellerProductVersionResponse(
			product.getMajorVersion() + "." + product.getPatchVersion(),
			product.getStatus().name(),
			product.getUpdatedAt().toLocalDate().toString(),
			product.getChangeReason(),
			product.getRejectionReason(),
			product.isHasContext(),
			product.isHasObjective(),
			product.isHasNuance(),
			product.isHasTone(),
			product.isHasExamples(),
			product.isHasExecution(),
			product.isHasRoleAssignment(),
			product.isChecklistRecorded()
		);
	}
}
