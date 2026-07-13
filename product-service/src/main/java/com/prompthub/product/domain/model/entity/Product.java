package com.prompthub.product.domain.model.entity;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.infra.persistence.converter.TagsConverter;
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

@Getter
@Entity
@Table(name = "product")
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

	@Column(name = "change_reason", length = 500)
	private String changeReason;

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

	@Column(name = "badge", length = 50)
	private String badge;

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

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public static Product create(
		UUID id,
		UUID sellerId,
		ProductType productType,
		String name,
		String description,
		String model,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		List<String> imageUrls,
		String content,
		String fileUrl,
		String externalUrl,
		List<String> tags
	) {
		validateTypeFields(productType, content, fileUrl, externalUrl);
		Product product = new Product();
		product.id = id;
		product.sellerId = sellerId;
		product.productType = productType;
		product.name = name;
		product.description = description;
		product.model = model;
		product.amountType = amountType;
		product.amount = amount;
		product.thumbnailUrl = thumbnailUrl;
		product.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
		product.content = content;
		product.fileUrl = fileUrl;
		product.externalUrl = externalUrl;
		product.tags = tags != null ? tags : new ArrayList<>();
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
		ProductType productType,
		String name,
		String description,
		String model,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		List<String> imageUrls,
		String content,
		String fileUrl,
		String externalUrl,
		List<String> tags,
		String changeReason,
		boolean isMajor
	) {
		validateTypeFields(productType, content, fileUrl, externalUrl);
		this.productType = productType;
		this.name = name;
		this.description = description;
		this.model = model;
		this.amountType = amountType;
		this.amount = amount;
		this.thumbnailUrl = thumbnailUrl;
		this.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
		this.content = content;
		this.fileUrl = fileUrl;
		this.externalUrl = externalUrl;
		this.tags = tags != null ? tags : new ArrayList<>();
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

	public void stop() {
		this.status = ProductStatus.STOPPED;
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

	public void incrementSalesCount() {
		this.salesCount++;
		this.updatedAt = LocalDateTime.now();
	}

	public void decrementSalesCount() {
		if (this.salesCount > 0) {
			this.salesCount--;
			this.updatedAt = LocalDateTime.now();
		}
	}

	public void incrementViewCount() {
		this.viewCount++;
		this.updatedAt = LocalDateTime.now();
	}

	public UUID familyRootId() {
		return this.parentId != null ? this.parentId : this.id;
	}

	public boolean isFamilyRoot() {
		return this.parentId == null;
	}

	public Product nextVersion(
		boolean isMajor,
		ProductType productType,
		String name,
		String description,
		String model,
		AmountType amountType,
		int amount,
		String thumbnailUrl,
		List<String> imageUrls,
		String content,
		String fileUrl,
		String externalUrl,
		List<String> tags,
		String changeReason
	) {
		validateTypeFields(productType, content, fileUrl, externalUrl);
		Product next = new Product();
		next.id = UUID.randomUUID();
		next.parentId = this.familyRootId();
		next.sellerId = this.sellerId;
		next.productType = productType;
		next.name = name;
		next.description = description;
		next.model = model;
		next.amountType = amountType;
		next.amount = amount;
		next.thumbnailUrl = thumbnailUrl;
		next.imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
		next.content = content;
		next.fileUrl = fileUrl;
		next.externalUrl = externalUrl;
		next.tags = tags != null ? tags : new ArrayList<>();
		next.changeReason = changeReason;
		next.badge = null; // 새 버전 row는 뱃지를 물려받지 않고 초기화한다(예: "신규" 뱃지가 계속 남는 걸 방지)
		if (isMajor) {
			next.majorVersion = (short) (this.majorVersion + 1);
			next.patchVersion = 0;
			next.status = ProductStatus.PENDING_REVIEW;
		} else {
			next.majorVersion = this.majorVersion;
			next.patchVersion = (short) (this.patchVersion + 1);
			next.status = ProductStatus.ON_SALE;
		}
		next.salesCount = 0;
		next.viewCount = 0;
		next.wishCount = 0;
		next.createdAt = LocalDateTime.now();
		next.updatedAt = LocalDateTime.now();
		return next;
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

	public void submitForReview() {
		if (this.status != ProductStatus.DRAFT && this.status != ProductStatus.REJECTED) {
			throw new IllegalStateException("검수 요청할 수 없는 상태입니다. current=" + this.status);
		}
		this.rejectionReason = null;
		this.status = ProductStatus.PENDING_REVIEW;
		this.updatedAt = LocalDateTime.now();
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

	public void revertToPendingReview() {
		if (this.status != ProductStatus.ON_SALE && this.status != ProductStatus.REJECTED) {
			throw new IllegalStateException("승인 또는 반려 상태의 상품만 검수 대기로 되돌릴 수 있습니다. current=" + this.status);
		}
		this.status = ProductStatus.PENDING_REVIEW;
		this.rejectionReason = null;
		this.updatedAt = LocalDateTime.now();
	}

	private static void validateTypeFields(
		ProductType productType, String content, String fileUrl, String externalUrl
	) {
		boolean hasContent = content != null && !content.isBlank();
		boolean hasFileUrl = fileUrl != null && !fileUrl.isBlank();
		boolean hasExternalUrl = externalUrl != null && !externalUrl.isBlank();

		boolean ok = switch (productType) {
			case PROMPT -> hasContent && !hasFileUrl && !hasExternalUrl;
			case PPT, EXCEL -> hasFileUrl && !hasContent && !hasExternalUrl;
			case NOTION -> hasExternalUrl && !hasContent && !hasFileUrl;
		};
		if (!ok) {
			throw new ProductException(ProductErrorCode.PRODUCT_TYPE_FIELD_MISMATCH);
		}
	}
}
