package com.prompthub.product.search.infra.batch;

import com.prompthub.product.search.application.ProductReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * family 집계 카운트(salesCount/viewCount/reviewCount/ratingAvg)를 10분마다 ES에
 * 반영한다. (es-1 §5) — 이벤트마다 문서를 갱신하지 않고 주기적으로 묶어서 반영해
 * 소형 ES 노드 부하를 줄인다. @EnableScheduling은 OutboxRelayConfig가 이미 켰다.
 */
@Component
@RequiredArgsConstructor
public class ProductCountSyncScheduler {

	private final ProductReindexService productReindexService;

	@Scheduled(fixedDelayString = "${prompthub.search.count-sync.fixed-delay-ms:600000}")
	public void syncCounts() {
		productReindexService.reindexAll();
	}
}
