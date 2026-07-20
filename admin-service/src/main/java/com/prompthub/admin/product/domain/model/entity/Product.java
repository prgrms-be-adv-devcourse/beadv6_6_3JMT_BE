package com.prompthub.admin.product.domain.model.entity;

import com.prompthub.admin.product.domain.model.enums.AmountType;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import com.prompthub.admin.product.domain.model.enums.ProductType;
import com.prompthub.admin.product.infrastructure.persistence.converter.TagsConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * product-service의 product_service.product 테이블을 읽기/쓰기하는 엔티티.
 * admin-service는 ddl-auto=none이므로 스키마를 변경하지 않는다.
 */
@Getter
@Entity
@Table(name = "product", schema = "product_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "parent_id")
	private UUID parentId;

	@Column(name = "seller_id", nullable = false)
	private UUID sellerId;

	@Column(name = "major_version", nullable = false)
	private short majorVersion;

	@Column(name = "patch_version", nullable = false)
	private short patchVersion;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "description", nullable = false, columnDefinition = "TEXT")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "product_type", nullable = false, length = 50)
	private ProductType productType;

	@Column(name = "model", length = 100)
	private String model;

	@Enumerated(EnumType.STRING)
	@Column(name = "amount_type", nullable = false)
	private AmountType amountType;

	@Column(name = "amount", nullable = false)
	private int amount;

	@Column(name = "thumbnail_url", columnDefinition = "TEXT")
	private String thumbnailUrl;

	@Convert(converter = TagsConverter.class)
	@Column(name = "image_urls", columnDefinition = "TEXT")
	private List<String> imageUrls = new ArrayList<>();

	@Column(name = "content", columnDefinition = "TEXT")
	private String content;

	@Column(name = "file_url", columnDefinition = "TEXT")
	private String fileUrl;

	@Column(name = "external_url", columnDefinition = "TEXT")
	private String externalUrl;

	@Convert(converter = TagsConverter.class)
	@Column(name = "tags")
	private List<String> tags = new ArrayList<>();

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

	@Column(name = "badge", length = 50)
	private String badge;

	@Column(name = "change_reason", length = 500)
	private String changeReason;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public UUID familyRootId() {
		return this.parentId != null ? this.parentId : this.id;
	}

	public void approve() {
		if (this.status != ProductStatus.PENDING_REVIEW) {
			throw new IllegalStateException("검수 대기 상태의 상품만 승인할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.ON_SALE;
		this.updatedAt = LocalDateTime.now();
	}

	public void reject(String reason) {
		if (this.status != ProductStatus.PENDING_REVIEW) {
			throw new IllegalStateException("검수 대기 상태의 상품만 반려할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.REJECTED;
		this.rejectionReason = reason;
		this.updatedAt = LocalDateTime.now();
	}

	public void supersede() {
		if (this.status != ProductStatus.ON_SALE) {
			throw new IllegalStateException("ON_SALE 상태의 상품만 SUPERSEDED로 전환할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.SUPERSEDED;
		this.updatedAt = LocalDateTime.now();
	}

	public void restoreFromSuperseded() {
		if (this.status != ProductStatus.SUPERSEDED) {
			throw new IllegalStateException("SUPERSEDED 상태의 상품만 ON_SALE로 복원할 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.ON_SALE;
		this.updatedAt = LocalDateTime.now();
	}

	public void revertToPendingReview() {
		if (this.status != ProductStatus.ON_SALE && this.status != ProductStatus.REJECTED) {
			throw new IllegalStateException("승인 또는 반려 상태의 상품만 검수 대기로 되돌릴 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.PENDING_REVIEW;
		this.rejectionReason = null;
		this.updatedAt = LocalDateTime.now();
	}
}
