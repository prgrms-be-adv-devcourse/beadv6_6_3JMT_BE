package com.prompthub.admin.seller.application.dto;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SellerRegisterSummaryResult(
	UUID registerId,
	UUID userId,
	String name,
	String email,
	String introduction,
	List<String> categories,
	String portfolioUrl,
	SellerRegisterStatus status,
	LocalDateTime submittedAt
) {
	public static SellerRegisterSummaryResult of(SellerRegister register, User user) {
		return new SellerRegisterSummaryResult(
			register.getSellerRegisterId(),
			register.getUserId(),
			user.getName(),
			user.getEmail(),
			register.getIntroduction(),
			List.copyOf(register.getCategories()),
			register.getPortfolioUrl(),
			register.getStatus(),
			register.getSubmittedAt()
		);
	}
}
