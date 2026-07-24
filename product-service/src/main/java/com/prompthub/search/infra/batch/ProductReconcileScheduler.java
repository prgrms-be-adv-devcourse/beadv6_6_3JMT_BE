package com.prompthub.search.infra.batch;

import com.prompthub.search.application.ProductReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RDB ON_SALE 전체와 ES 색인 전체를 주기적으로 비교해 맞춘다. 실시간 이벤트가 없는
 * admin-service발 변화(승인/승인취소)만 여기서 잡아낸다 — product-service 자체 발 변화
 * (생성/패치/판매중단)는 ProductSearchEventHandler가 실시간으로 반영한다(#377).
 * 주기는 원래 7일이었으나(#376), #377에서 공개 검색 API가 ES를 라이브로 쓰게 되면서
 * 15~30초로 단축했다 — 카탈로그 규모상 매 실행 비용은 무시할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class ProductReconcileScheduler {

	private final ProductReindexService productReindexService;

	@Scheduled(fixedDelayString = "${prompthub.search.reconcile.fixed-delay-ms:20000}")
	public void reconcile() {
		productReindexService.reconcileAll();
	}
}
