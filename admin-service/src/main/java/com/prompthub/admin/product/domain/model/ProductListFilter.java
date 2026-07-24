package com.prompthub.admin.product.domain.model;

import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import java.util.List;
import java.util.UUID;

/**
 * admin 상품 목록 조회 필터. status가 null이면 admin 노출 상태 전체(ALL)를 의미한다.
 * keywordSellerIds는 keyword로 판매자 닉네임을 선조회한 결과로,
 * "상품명 LIKE keyword OR sellerId IN keywordSellerIds" 조건에 쓰인다.
 */
public record ProductListFilter(
	ProductStatus status,
	String keyword,
	List<UUID> keywordSellerIds
) {
}
