package com.prompthub.search.infra.batch;

import com.prompthub.search.application.ProductReindexService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * family 집계 카운트(salesCount/viewCount/ratingAvg)를 10분마다 ES에 반영한다.
 * (es-1 §5) — 지난 주기 이후 Product 또는 Review가 바뀐 family만 부분 갱신해
 * 소형 ES 노드 부하를 줄인다. lastSyncedAt은 인메모리라 재시작 시 리셋되지만,
 * 그 경우에도 "재시작 시점부터" 범위로 자연스럽게 좁혀질 뿐 실패로 이어지지 않는다.
 * @EnableScheduling은 OutboxRelayConfig가 이미 켰다.
 */
@Component
@RequiredArgsConstructor
public class ProductCountSyncScheduler {

	private final ProductReindexService productReindexService;

	private LocalDateTime lastSyncedAt = LocalDateTime.now();

	@Scheduled(fixedDelayString = "${prompthub.search.count-sync.fixed-delay-ms:600000}")
	public void syncCounts() {
		LocalDateTime syncStartedAt = LocalDateTime.now();
		productReindexService.syncChangedCounts(lastSyncedAt);
		lastSyncedAt = syncStartedAt;
	}
}
