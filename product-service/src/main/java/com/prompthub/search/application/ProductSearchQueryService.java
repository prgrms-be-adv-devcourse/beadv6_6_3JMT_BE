package com.prompthub.search.application;

import org.springframework.data.domain.Pageable;

/**
 * ES 목록·검색 조회 포트. product 패키지(ProductQueryService)가 이 포트를 통해 ES 조회를
 * 먼저 시도하고, 실패 시 기존 RDB 경로로 폴백한다.
 */
public interface ProductSearchQueryService {

	ProductSearchPageResult search(String keyword, String productType, String sort, Pageable pageable);
}
