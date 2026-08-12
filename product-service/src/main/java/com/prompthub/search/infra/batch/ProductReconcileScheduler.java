package com.prompthub.search.infra.batch;

import com.prompthub.search.application.indexing.ProductReindexService;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RDB와 ES 색인을 주기적으로 맞춘다.
 *
 * <p><b>짧은 주기 tick</b>은 마지막 성공 시각 이후 변경분만 반영한다. 기동 후 첫 tick과
 * 이전 tick이 실패(인덱스 부재)한 경우에는 전체 재조정을 돌려 기준점을 만든다 — 워터마크를
 * 메모리에 두므로 재시작 시 유실되기 때문이다.
 *
 * <p><b>주기 값의 근거</b>: 이 주기는 admin 승인 → 검색 노출까지의 지연 예산이다. 관리자가
 * 승인한 뒤 스토어프론트에서 노출을 확인하는 작업 흐름(화면 이동·검색, 대략 5~15초)을 크게
 * 넘기지 않아야 한다. 넘기면 "승인이 반영되지 않았다"는 오인과 재승인·오탐 신고를 유발한다.
 * 증분 전환 후 변경이 없는 사이클의 비용은 조회 2건이므로 비용이 이 값을 제약하지 않는다 —
 * 기준은 사용자 경험이다.
 *
 * <p><b>하루 1회 전체 스윕</b>은 증분이 잡지 못하는 ES 고아 문서를 정리한다.
 */
@Component
@RequiredArgsConstructor
public class ProductReconcileScheduler {

	/**
	 * 워터마크를 뒤로 물려 잡는 여유. 트랜잭션이 커밋된 시각({@code updatedAt})보다 조회에
	 * 보이는 시점이 늦을 수 있어, 워터마크를 실행 시각 그대로 쓰면 그 사이에 낀 행이 영구히
	 * 누락된다. 재조정은 멱등이라 겹쳐 처리해도 무해하다.
	 */
	private static final Duration WATERMARK_OVERLAP = Duration.ofSeconds(30);

	private final ProductReindexService productReindexService;

	/**
	 * 마지막으로 재조정에 성공한 시각. 실패한 사이클에서는 전진시키지 않는다 —
	 * 전진시키면 그 구간의 변경분을 영구히 놓친다.
	 */
	private volatile LocalDateTime lastSucceededAt;

	@Scheduled(fixedDelayString = "${prompthub.search.reconcile.fixed-delay-ms:20000}")
	public void reconcile() {
		LocalDateTime startedAt = LocalDateTime.now();
		boolean succeeded = (lastSucceededAt == null)
			? productReindexService.reconcileAll()
			: productReindexService.reconcileChanged(lastSucceededAt.minus(WATERMARK_OVERLAP));

		if (succeeded) {
			lastSucceededAt = startedAt;
		}
	}

	@Scheduled(cron = "${prompthub.search.reconcile.full-sweep-cron:0 0 4 * * *}")
	public void fullSweep() {
		LocalDateTime startedAt = LocalDateTime.now();
		if (productReindexService.reconcileAll()) {
			lastSucceededAt = startedAt;
		}
	}
}
