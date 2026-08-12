package com.prompthub.product.infra.batch.inspection;

import com.prompthub.product.application.service.inspection.ProductInspectionRequestRetryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * stale-after를 넘긴 PENDING_REVIEW 상품을 찾아 최대 1회 재발행한다(2026-08-05 로드맵 PR4).
 * 실제 선점(claim)은 {@link ProductInspectionRequestRetryService}의 조건부 UPDATE가 담당하므로
 * 이 스케줄러는 후보 조회와 순회만 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ProductInspectionRequestRetryProperties.class)
public class ProductInspectionRequestRetryScheduler {

	private final ProductInspectionRequestRetryService productInspectionRequestRetryService;
	private final ProductInspectionRequestRetryProperties properties;

	@Scheduled(fixedDelayString = "${prompthub.product.inspection-request-retry.fixed-delay-ms:60000}")
	public void retryStaleInspectionRequests() {
		LocalDateTime cutoff = LocalDateTime.now().minus(properties.staleAfter());
		List<UUID> candidates = productInspectionRequestRetryService
			.findStaleInspectionRequestCandidateIds(cutoff, properties.batchSize());
		log.info("검수 요청 stale 재발행 사이클 시작. candidateCount={}", candidates.size());

		int failed = 0;
		for (UUID productId : candidates) {
			try {
				productInspectionRequestRetryService.retryStaleInspectionRequest(productId, cutoff);
			} catch (RuntimeException e) {
				// 한 상품의 재발행 실패(S3·DB 일시 오류 등)가 같은 사이클의 나머지 후보까지
				// 막지 않게 한다 — 실패한 후보는 retryCount가 아직 0이므로 다음 tick에서 다시 잡힌다.
				failed++;
				log.error("검수 요청 stale 재발행 실패. productId={}", productId, e);
			}
		}
		log.info("검수 요청 stale 재발행 사이클 종료. candidateCount={}, failed={}", candidates.size(), failed);
	}
}
