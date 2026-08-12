package com.prompthub.product.application.service.inspection;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ai-events(PRODUCT_INSPECTION_COMPLETED) 처리. (루트 kafka-event.md 참고)
 * Product.approve()/reject()가 PENDING_REVIEW 가드를 갖고 있어 자연 멱등이다 —
 * 이미 처리된 상품에 대한 중복 이벤트는 IllegalStateException을 잡아 조용히 스킵한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductInspectionResultHandler {

	private final ProductRepository productRepository;

	@Transactional
	public void apply(UUID productId, boolean approved, String rejectionReason, InspectionChecklist checklist) {
		Product product = productRepository.findById(productId).orElse(null);
		if (product == null) {
			log.info("검수 대상 상품을 찾을 수 없어 결과를 스킵함. productId={}", productId);
			return;
		}
		try {
			if (approved) {
				product.approve(checklist);
				supersedePreviousVersions(product);
			} else {
				product.reject(rejectionReason, checklist);
			}
		} catch (IllegalStateException e) {
			log.info("이미 처리된 검수 결과라 스킵함. productId={}, currentStatus={}",
				productId, product.getStatus());
			return;
		}
		productRepository.save(product);
	}

	/**
	 * 승인으로 새 버전이 판매를 시작하는 순간, 같은 가족에서 팔리던 이전 버전을 교대시킨다.
	 * 이 호출이 빠지면 메이저 수정이 승인될 때마다 가족에 ON_SALE 행이 누적된다(#699).
	 * ES 반영은 승인과 같은 경로다 — supersede가 updatedAt을 갱신해 재조정이 집어간다.
	 */
	private void supersedePreviousVersions(Product approvedProduct) {
		List<Product> family = productRepository.findAllByFamilyRootIds(List.of(approvedProduct.familyRootId()));
		for (Product member : family) {
			if (!member.getId().equals(approvedProduct.getId()) && member.getStatus() == ProductStatus.ON_SALE) {
				member.supersede();
				productRepository.save(member);
			}
		}
	}
}
