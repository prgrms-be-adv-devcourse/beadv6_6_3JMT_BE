package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.SellerRegister;
import com.prompthub.admin.user.entity.enums.SellerRegisterStatus;

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
