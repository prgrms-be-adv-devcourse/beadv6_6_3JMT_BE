package com.prompthub.ai.inspection.domain.model;

/**
 * AI 검수 판정 결과. approved=false일 때만 rejectionReason이 채워진다.
 */
public record InspectionVerdict(boolean approved, String rejectionReason) {
}
