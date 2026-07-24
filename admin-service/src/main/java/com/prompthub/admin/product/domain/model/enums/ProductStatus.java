package com.prompthub.admin.product.domain.model.enums;

import java.util.List;

public enum ProductStatus {
	DRAFT,
	PENDING_REVIEW,
	ON_SALE,
	REJECTED,
	STOPPED,
	SUPERSEDED;

	// admin 상품 목록 화면이 다루는 상태 — 전체(ALL) 조회 시 이 세 상태만 노출한다.
	public static List<ProductStatus> adminVisibleStatuses() {
		return List.of(PENDING_REVIEW, ON_SALE, REJECTED);
	}
}
