package com.prompthub.search.application.query;

import java.util.List;
import org.springframework.data.domain.Pageable;

/**
 * ES 목록·검색 조회 포트. product 패키지(ProductQueryService)가 이 포트를 통해 ES 조회를
 * 먼저 시도하고, 실패 시 기존 RDB 경로로 폴백한다.
 */
public interface ProductSearchQueryPort {

	ProductSearchPageResult search(String keyword, String productType, String sort, Pageable pageable);

	/**
	 * 검색어로 시작하는 상품명을 판매량 순으로 제안한다.
	 *
	 * <p>목록·검색과 달리 RDB 폴백이 없다 — RDB에는 대응하는 조회가 없고, 자동완성은 실패해도
	 * 드롭다운이 뜨지 않을 뿐 사용자가 검색 자체를 못 하게 되지는 않는다.
	 *
	 * @return 상품명 목록. 최대 {@code limit}개
	 */
	List<String> suggest(String keyword, int limit);
}
