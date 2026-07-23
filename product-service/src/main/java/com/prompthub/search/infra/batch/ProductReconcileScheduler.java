package com.prompthub.search.infra.batch;

import com.prompthub.search.application.ProductReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RDB ON_SALE 전체와 ES 색인 전체를 7일마다 비교해 맞춘다. 실시간 이벤트가 없는
 * admin-service발 변화(승인/승인취소)와 삭제/판매중단을 여기서 최종적으로 잡아낸다.
 * (2026-07-23-es-376-search-sync-redesign-design.md §2, §8 — 공개 검색 API가 ES를
 * 아직 라이브로 쓰지 않는 동안만 유효한 전제. #377에서 재검토 필요.)
 */
@Component
@RequiredArgsConstructor
public class ProductReconcileScheduler {

	private final ProductReindexService productReindexService;

	@Scheduled(fixedDelayString = "${prompthub.search.reconcile.fixed-delay-ms:604800000}")
	public void reconcile() {
		productReindexService.reconcileAll();
	}
}
