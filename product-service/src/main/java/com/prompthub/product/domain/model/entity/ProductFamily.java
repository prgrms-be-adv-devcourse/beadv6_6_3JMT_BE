package com.prompthub.product.domain.model.entity;

import com.prompthub.product.domain.model.enums.ProductStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ProductFamily {

	private static final List<ProductStatus> WISHLIST_PRIORITY = List.of(
		ProductStatus.ON_SALE,
		ProductStatus.STOPPED,
		ProductStatus.REJECTED,
		ProductStatus.PENDING_REVIEW,
		ProductStatus.DRAFT
	);

	private static final List<ProductStatus> SELLER_PRIORITY = List.of(
		ProductStatus.PENDING_REVIEW,
		ProductStatus.ON_SALE,
		ProductStatus.REJECTED,
		ProductStatus.DRAFT,
		ProductStatus.STOPPED
	);

	private final UUID familyRootId;
	private final List<Product> members;

	private ProductFamily(UUID familyRootId, List<Product> members) {
		this.familyRootId = familyRootId;
		this.members = members;
	}

	public static ProductFamily of(UUID familyRootId, List<Product> members) {
		return new ProductFamily(familyRootId, members);
	}

	public UUID familyRootId() {
		return familyRootId;
	}

	public List<Product> members() {
		return members;
	}

	public Optional<Product> currentOnSale() {
		return latestByStatus(ProductStatus.ON_SALE);
	}

	public Optional<Product> currentForWishlist() {
		return firstMatchByPriority(WISHLIST_PRIORITY);
	}

	public Optional<Product> currentForSeller() {
		return firstMatchByPriority(SELLER_PRIORITY);
	}

	public Optional<Product> pendingReview() {
		return latestByStatus(ProductStatus.PENDING_REVIEW);
	}

	public Optional<Product> mostRecentSuperseded() {
		return latestByStatus(ProductStatus.SUPERSEDED);
	}

	public boolean hasEverBeenOnSale() {
		return members.stream().anyMatch(p ->
			p.getStatus() == ProductStatus.ON_SALE || p.getStatus() == ProductStatus.SUPERSEDED);
	}

	public List<Product> publicHistory() {
		return members.stream()
			.filter(p -> p.getStatus() == ProductStatus.ON_SALE || p.getStatus() == ProductStatus.SUPERSEDED)
			.sorted(versionDescending())
			.toList();
	}

	public List<Product> sellerHistory() {
		return members.stream().sorted(versionDescending()).toList();
	}

	private Optional<Product> firstMatchByPriority(List<ProductStatus> priority) {
		for (ProductStatus status : priority) {
			Optional<Product> match = latestByStatus(status);
			if (match.isPresent()) {
				return match;
			}
		}
		return Optional.empty();
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

	private Comparator<Product> versionDescending() {
		return versionAscending().reversed();
	}
}
