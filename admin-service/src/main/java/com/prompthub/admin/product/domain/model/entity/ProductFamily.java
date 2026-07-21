package com.prompthub.admin.product.domain.model.entity;

import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 동일한 familyRootId를 공유하는 Product 집합에 대한 도메인 집합체.
 * DB에 매핑되지 않는 순수 객체다.
 */
public class ProductFamily {

	private final UUID familyRootId;
	private final List<Product> members;

	private ProductFamily(UUID familyRootId, List<Product> members) {
		this.familyRootId = familyRootId;
		this.members = members;
	}

	public static ProductFamily of(UUID familyRootId, List<Product> members) {
		return new ProductFamily(familyRootId, members);
	}

	/**
	 * 현재 ON_SALE 상태인 최신 버전 상품을 반환한다.
	 */
	public Optional<Product> currentOnSale() {
		return latestByStatus(ProductStatus.ON_SALE);
	}

	/**
	 * 가장 최근에 SUPERSEDED된 상품을 반환한다 (revert 시 복원 대상).
	 */
	public Optional<Product> mostRecentSuperseded() {
		return latestByStatus(ProductStatus.SUPERSEDED);
	}

	private Optional<Product> latestByStatus(ProductStatus status) {
		return members.stream()
			.filter(p -> p.getStatus() == status)
			.max(versionAscending());
	}

	private Comparator<Product> versionAscending() {
		return Comparator
			.comparingInt((Product p) -> (int) p.getMajorVersion())
			.thenComparingInt(p -> (int) p.getPatchVersion());
	}
}
