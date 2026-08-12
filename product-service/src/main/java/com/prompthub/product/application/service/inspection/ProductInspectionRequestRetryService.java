package com.prompthub.product.application.service.inspection;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검수 요청 발행이 broker 전송 실패나 ai-service 결과 유실로 오래 PENDING_REVIEW에 머문 상품을
 * 최대 1회 자동 재발행한다(2026-08-05 로드맵 PR4). {@link ProductInspectionRequestRetryScheduler}가
 * 주기적으로 호출한다.
 *
 * <p>완전한 전달 보장은 아니다 — 재발행까지 실패하면 로그만 남기고 더 반복하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductInspectionRequestRetryService {

	private final ProductRepository productRepository;
	private final ProductInspectionRequestPublisher productInspectionRequestPublisher;

	@Transactional(readOnly = true)
	public List<UUID> findStaleInspectionRequestCandidateIds(LocalDateTime cutoff, int batchSize) {
		return productRepository.findStaleInspectionRequestCandidateIds(cutoff, batchSize);
	}

	/**
	 * 상품 하나를 짧은 개별 트랜잭션에서 다시 확인·선점한다. 선점은 조건부 UPDATE라, 다른
	 * 실행이 이미 같은 상품을 집었으면 여기서는 조용히 건너뛴다 — 발행은 총 1회만 일어난다.
	 */
	@Transactional
	public void retryStaleInspectionRequest(UUID productId, LocalDateTime cutoff) {
		if (!productRepository.claimInspectionRequestRetry(productId, cutoff)) {
			return;
		}
		Product product = productRepository.findById(productId).orElse(null);
		if (product == null) {
			log.warn("검수 재발행을 선점했지만 상품을 찾을 수 없음. productId={}", productId);
			return;
		}
		productInspectionRequestPublisher.publish(product);
	}
}
