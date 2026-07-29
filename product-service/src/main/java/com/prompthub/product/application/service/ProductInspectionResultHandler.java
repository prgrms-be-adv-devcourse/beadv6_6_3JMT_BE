package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
import com.prompthub.product.domain.repository.ProductRepository;
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
}
