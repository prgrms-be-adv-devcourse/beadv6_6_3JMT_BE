package com.prompthub.admin.seller.application.dto;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SellerRegisterReviewResult(
	UUID registerId,
	UUID userId,
	SellerRegisterStatus status,
	String rejectReason,
	LocalDateTime reviewedAt
) {
	public static SellerRegisterReviewResult from(SellerRegister register) {
		return new SellerRegisterReviewResult(
			register.getSellerRegisterId(),
			register.getUserId(),
			register.getStatus(),
			register.getRejectReason(),
			register.getReviewedAt()
		);
	}
}
