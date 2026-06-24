package com.prompthub.product.domain.model.entity;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "seller_id", nullable = false)
	private UUID sellerId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id")
	private Category category;

	@Column(name = "category_id", insertable = false, updatable = false)
	private UUID categoryId;

	@Column(name = "major_version", nullable = false)
	private short majorVersion;

	@Column(name = "patch_version", nullable = false)
	private short patchVersion;

	@Column(name = "change_reason", length = 500)
	private String changeReason;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "description", nullable = false)
	private String description;

	@Column(name = "product_type", nullable = false, length = 50)
	private String productType;

	@Enumerated(EnumType.STRING)
	@Column(name = "amount_type", nullable = false)
	private AmountType amountType;

	@Column(name = "amount", nullable = false)
	private int amount;

	@Column(name = "thumbnail_url", length = 500)
	private String thumbnailUrl;

	@Column(name = "content")
	private String content;

	@Column(name = "badge", length = 50)
	private String badge;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ProductStatus status;

	@Column(name = "rejection_reason", length = 1000)
	private String rejectionReason;

	@Column(name = "sales_count", nullable = false)
	private int salesCount;

	@Column(name = "view_count", nullable = false)
	private int viewCount;

	@Column(name = "wish_count", nullable = false)
	private int wishCount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public static Product create(
		UUID sellerId,
		Category category,
		String name,
		String description,
		String productType,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		String content
	) {
		Product product = new Product();
		product.id = UUID.randomUUID();
		product.sellerId = sellerId;
		product.category = category;
		product.categoryId = category != null ? category.getId() : null;
		product.name = name;
		product.description = description;
		product.productType = productType;
		product.amountType = amountType;
		product.amount = amount;
		product.thumbnailUrl = thumbnailUrl;
		product.content = content;
		product.majorVersion = 1;
		product.patchVersion = 0;
		product.status = ProductStatus.DRAFT;
		product.salesCount = 0;
		product.viewCount = 0;
		product.wishCount = 0;
		product.createdAt = LocalDateTime.now();
		product.updatedAt = LocalDateTime.now();
		return product;
	}

	public void update(
		Category category,
		String name,
		String description,
		String productType,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		String content,
		String changeReason,
		boolean isMajor
	) {
		this.category = category;
		this.categoryId = category != null ? category.getId() : null;
		this.name = name;
		this.description = description;
		this.productType = productType;
		this.amountType = amountType;
		this.amount = amount;
		this.thumbnailUrl = thumbnailUrl;
		this.content = content;
		this.changeReason = changeReason;
		if (isMajor) {
			this.majorVersion++;
			this.patchVersion = 0;
			this.status = ProductStatus.PENDING_REVIEW;
		} else {
			this.patchVersion++;
		}
		this.updatedAt = LocalDateTime.now();
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
		this.status = ProductStatus.STOPPED;
		this.updatedAt = LocalDateTime.now();
	}

	public boolean isOwnedBy(UUID userId) {
		return this.sellerId.equals(userId);
	}
}
