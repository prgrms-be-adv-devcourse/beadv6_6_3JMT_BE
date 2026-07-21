package com.prompthub.admin.seller.presentation.dto.response;

import com.prompthub.admin.seller.application.dto.SellerRegisterSummaryResult;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "관리자 — 판매자 신청 목록 항목")
public record SellerRegisterResponse(
	@Schema(description = "판매자 등록 신청 ID")
	String registerId,
	@Schema(description = "신청자 ID")
	String userId,
	@Schema(description = "신청자 이름", example = "이서아")
	String name,
	@Schema(description = "신청자 이메일", example = "seoah@example.com")
	String email,
	@Schema(description = "판매자 소개", nullable = true)
	String introduction,
	@Schema(description = "주력 카테고리", example = "[\"이미지 생성\"]")
	List<String> categories,
	@Schema(description = "포트폴리오 URL", nullable = true)
	String portfolioUrl,
	@Schema(description = "신청 상태 (pending | approved | rejected)", example = "pending")
	String status,
	@Schema(description = "신청 일시 (ISO 8601)", example = "2026-06-14T00:00:00")
	LocalDateTime submittedAt
) {
	public static SellerRegisterResponse from(SellerRegisterSummaryResult result) {
		return new SellerRegisterResponse(
			result.registerId().toString(),
			result.userId().toString(),
			result.name(),
			result.email(),
			result.introduction(),
			result.categories(),
			result.portfolioUrl(),
			mapStatus(result.status()),
			result.submittedAt()
		);
	}

	private static String mapStatus(SellerRegisterStatus status) {
		return switch (status) {
			case PENDING -> "pending";
			case APPROVED -> "approved";
			case REJECTED -> "rejected";
		};
	}
}
