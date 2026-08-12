package com.prompthub.product.domain.model.entity;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.enums.ProductVersionType;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.model.vo.ProductContentHash;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

	/**
	 * 복제 판정용 본문 해시(PROMPT 전용). 검수 주체가 같은 값을 가진 타 판매자 상품을 찾는다.
	 *
	 * <p>{@code embedding_source_hash}와 다른 값이다 — 그쪽은 "글이 바뀌었나", 이쪽은
	 * "같은 글이 이미 있나"를 묻는다. {@link ProductContentHash} 주석 참고.
	 */
	@Column(name = "content_hash", length = 64)
	private String contentHash;

	/**
	 * content_hash가 확정된 시각(DB 트리거가 찍음). 중복 판정의 순서 기준 —
	 * created_at/updated_at은 콘텐츠와 무관하게 밀려 원본·복제 순서가 뒤집힐 수 있어 쓰지 않는다.
	 *
	 * <p>앱이 아니라 DB 단일 시계로 찍어야 멀티 파드 시계 스큐로 인한 오판을 막을 수 있어
	 * 읽기 전용으로 매핑한다. JPA가 이 값을 쓰지 않으므로, persist 직후 값이 필요하면
	 * flush 후 재조회하거나 서브쿼리로 참조한다.
	 */
	@Column(name = "content_hash_at", insertable = false, updatable = false)
	private LocalDateTime contentHashAt;

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

	@Column(name = "has_context", nullable = false)
	private boolean hasContext;

	@Column(name = "has_objective", nullable = false)
	private boolean hasObjective;

	@Column(name = "has_nuance", nullable = false)
	private boolean hasNuance;

	@Column(name = "has_tone", nullable = false)
	private boolean hasTone;

	@Column(name = "has_examples", nullable = false)
	private boolean hasExamples;

	@Column(name = "has_execution", nullable = false)
	private boolean hasExecution;

	@Column(name = "has_role_assignment", nullable = false)
	private boolean hasRoleAssignment;

	@Column(name = "checklist_recorded", nullable = false)
	private boolean checklistRecorded;

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

	/**
	 * 현재 검수 회차가 시작된 시각. 오래 대기한 PENDING_REVIEW를 찾는 stale 판정 기준이다.
	 * 단순 수정 시각인 {@code updatedAt}은 재사용하지 않는다 — 재발행 때문에 ES 재대사 대상이
	 * 불필요하게 바뀌는 부작용과 "일반 수정"·"검수 대기 시작"의 의미가 섞이는 걸 피한다.
	 */
	@Column(name = "inspection_requested_at")
	private LocalDateTime inspectionRequestedAt;

	/** 현재 검수 회차의 자동 재발행 횟수. 최대 1이며, 조건부 UPDATE로 원자적으로 선점된다. */
	@Column(name = "inspection_request_retry_count", nullable = false)
	private int inspectionRequestRetryCount;

	public static Product create(UUID id, UUID sellerId, ProductContent productContent) {
		Product product = new Product();
		product.id = id;
		product.sellerId = sellerId;
		product.applyContent(productContent);
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

	/** 판매 전 DRAFT row를 같은 version(1.0)에서 콘텐츠만 보정한다 — 임시저장은 버전을 올리지 않는다. */
	public void updateDraftContent(ProductContent productContent) {
		if (this.status != ProductStatus.DRAFT) {
			throw new IllegalStateException("DRAFT 상태의 상품만 이 방식으로 수정할 수 있습니다. current=" + this.status);
		}
		applyContent(productContent);
		this.updatedAt = LocalDateTime.now();
	}

	/** 판매 후 반려된 row를 같은 version에서 콘텐츠만 보정한다 — 재검수는 {@link #submitForReview()}를 따로 호출한다. */
	public void updateRejectedContent(ProductContent productContent) {
		if (this.status != ProductStatus.REJECTED) {
			throw new IllegalStateException("REJECTED 상태의 상품만 이 방식으로 수정할 수 있습니다. current=" + this.status);
		}
		applyContent(productContent);
		this.updatedAt = LocalDateTime.now();
	}

	/** 유형별 핵심 산출물이나 FREE/PAID 전환이면 MAJOR, 그 외 변경만 있으면 PATCH, 변화 없으면 empty(no-op). */
	public Optional<ProductVersionType> determineVersionType(ProductContent candidate) {
		boolean coreOutputChanged = switch (this.productType) {
			case PROMPT -> !Objects.equals(this.content, candidate.content());
			case NOTION -> !Objects.equals(this.externalUrl, candidate.externalUrl());
			case PPT, EXCEL -> !Objects.equals(this.fileUrl, candidate.fileUrl());
		};
		if (coreOutputChanged || this.amountType != candidate.amountType()) {
			return Optional.of(ProductVersionType.MAJOR);
		}

		boolean metadataChanged = !Objects.equals(this.name, candidate.name())
			|| !Objects.equals(this.description, candidate.description())
			|| !Objects.equals(this.model, candidate.model())
			|| !Objects.equals(this.thumbnailUrl, candidate.thumbnailUrl())
			|| !this.imageUrls.equals(candidate.imageUrls())
			|| !this.tags.equals(candidate.tags());
		if (metadataChanged || this.amount != candidate.amount()) {
			return Optional.of(ProductVersionType.PATCH);
		}

		return Optional.empty();
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

	/** id는 application이 미리 생성해 넘긴다 — 파일을 그 경로로 승격해야 해서 도메인이 내부 생성하면 안 된다. */
	public Product createNextVersion(UUID nextProductId, ProductVersionType versionType, ProductContent productContent, String changeReason) {
		Product next = new Product();
		next.id = nextProductId;
		next.parentId = this.familyRootId();
		next.sellerId = this.sellerId;
		next.applyContent(productContent);
		next.changeReason = changeReason;
		next.badge = null; // 새 버전 row는 뱃지를 물려받지 않고 초기화한다(예: "신규" 뱃지가 계속 남는 걸 방지)
		if (versionType == ProductVersionType.MAJOR) {
			next.majorVersion = (short) (this.majorVersion + 1);
			next.patchVersion = 0;
			next.status = ProductStatus.PENDING_REVIEW;
			next.startInspectionRequest();
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

	public void submitForReview() {
		if (this.status != ProductStatus.DRAFT && this.status != ProductStatus.REJECTED) {
			throw new IllegalStateException("검수 요청할 수 없는 상태입니다. current=" + this.status);
		}
		this.rejectionReason = null;
		this.status = ProductStatus.PENDING_REVIEW;
		this.updatedAt = LocalDateTime.now();
		startInspectionRequest();
	}

	public void approve(InspectionChecklist checklist) {
		if (this.status != ProductStatus.PENDING_REVIEW) {
			throw new IllegalStateException("PENDING_REVIEW 상태의 상품만 승인할 수 있습니다. current=" + this.status);
		}
		applyInspectionChecklist(checklist);
		this.status = ProductStatus.ON_SALE;
		this.updatedAt = LocalDateTime.now();
	}

	public void reject(String reason, InspectionChecklist checklist) {
		if (this.status != ProductStatus.PENDING_REVIEW) {
			throw new IllegalStateException("PENDING_REVIEW 상태의 상품만 반려할 수 있습니다. current=" + this.status);
		}
		applyInspectionChecklist(checklist);
		this.status = ProductStatus.REJECTED;
		this.rejectionReason = reason;
		this.updatedAt = LocalDateTime.now();
	}

	/** 새 검수 회차 시작 — 대기 시각을 다시 찍고 재발행 횟수를 0으로 초기화한다. */
	private void startInspectionRequest() {
		this.inspectionRequestedAt = LocalDateTime.now();
		this.inspectionRequestRetryCount = 0;
	}

	private void applyInspectionChecklist(InspectionChecklist checklist) {
		this.hasContext = checklist.hasContext();
		this.hasObjective = checklist.hasObjective();
		this.hasNuance = checklist.hasNuance();
		this.hasTone = checklist.hasTone();
		this.hasExamples = checklist.hasExamples();
		this.hasExecution = checklist.hasExecution();
		this.hasRoleAssignment = checklist.hasRoleAssignment();
		this.checklistRecorded = true;
	}

	private void applyContent(ProductContent productContent) {
		this.productType = productContent.productType();
		this.name = productContent.name();
		this.description = productContent.description();
		this.model = productContent.model();
		this.amountType = productContent.amountType();
		this.amount = productContent.amount();
		this.thumbnailUrl = productContent.thumbnailUrl();
		this.imageUrls = productContent.imageUrls();
		this.content = productContent.content();
		this.fileUrl = productContent.fileUrl();
		this.externalUrl = productContent.externalUrl();
		this.tags = productContent.tags();
		// 여기서 계산해야 create·submitForReview 대상 MAJOR row·createNextVersion(MAJOR) 세 경로가
		// 모두 덮인다. submitForReview()에만 두면 뒤의 둘이 새서, 그 경로로 올라온 상품은 검사도
		// 안 받고 나중에 남이 복제해도 대조에 걸리지 않는다.
		this.contentHash = ProductContentHash.of(productContent);
	}
}
