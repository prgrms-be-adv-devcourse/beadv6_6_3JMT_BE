package com.prompthub.search.infra.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.search.application.indexing.ProductReindexService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * watermark(lastSucceededAt) 전진 규칙을 검증한다 — 실패한 사이클에서 전진시키면 그 구간의
 * 변경분을 영구히 놓친다(PR5 로드맵 I-2).
 */
@ExtendWith(MockitoExtension.class)
class ProductReconcileSchedulerTest {

	@Mock
	private ProductReindexService productReindexService;

	private ProductReconcileScheduler scheduler;

	private ProductReconcileScheduler newScheduler() {
		return new ProductReconcileScheduler(productReindexService);
	}

	@Test
	@DisplayName("처음 실행되면 watermark가 없어 전체 재조정을 돈다")
	void reconcile_firstRun_runsFullReconcile() {
		scheduler = newScheduler();
		given(productReindexService.reconcileAll()).willReturn(true);

		scheduler.reconcile();

		then(productReindexService).should().reconcileAll();
		then(productReindexService).should(org.mockito.Mockito.never()).reconcileChanged(any());
	}

	@Test
	@DisplayName("성공하면 watermark를 전진시켜 다음 tick은 증분 경로를 탄다")
	void reconcile_succeeds_advancesWatermarkForNextIncrementalRun() {
		scheduler = newScheduler();
		given(productReindexService.reconcileAll()).willReturn(true);
		given(productReindexService.reconcileChanged(any())).willReturn(true);

		scheduler.reconcile();
		Object watermarkAfterFirstRun = ReflectionTestUtils.getField(scheduler, "lastSucceededAt");
		assertThat(watermarkAfterFirstRun).isNotNull();

		scheduler.reconcile();

		ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		then(productReindexService).should().reconcileChanged(sinceCaptor.capture());
		// 30초 overlap을 두고 이전 성공 시각보다 조금 이르게 조회해야 그 사이 낀 변경을 놓치지 않는다.
		assertThat(sinceCaptor.getValue()).isBefore((LocalDateTime) watermarkAfterFirstRun);
	}

	@Test
	@DisplayName("reconcileAll이 false(인덱스 없음)를 반환하면 watermark를 전진시키지 않는다")
	void reconcile_reconcileAllReturnsFalse_doesNotAdvanceWatermark() {
		scheduler = newScheduler();
		given(productReindexService.reconcileAll()).willReturn(false);

		scheduler.reconcile();
		scheduler.reconcile();

		// watermark가 여전히 없으므로 두 번째 실행도 전체 재조정을 다시 시도해야 한다.
		then(productReindexService).should(org.mockito.Mockito.times(2)).reconcileAll();
		then(productReindexService).should(org.mockito.Mockito.never()).reconcileChanged(any());
	}

	@Test
	@DisplayName("bulk 반영이 예외로 실패하면 watermark를 전진시키지 않아 다음 tick이 같은 변경을 다시 반영한다")
	void reconcile_throwsOnFailure_doesNotAdvanceWatermark() {
		scheduler = newScheduler();
		// 첫 호출은 실패, 두 번째 호출(다음 tick)은 성공 — 재호출 시 given()이 mock을 다시
		// 부르는 mockito 특성상, willThrow 이후 별도 given()으로 재스텁하면 그 재스텁 호출
		// 자체가 던져버린다. 하나의 체인에서 연속 스텁으로 지정한다.
		given(productReindexService.reconcileAll())
			.willThrow(new IllegalStateException("ES bulk item failures"))
			.willReturn(true);

		assertThatThrownBy(() -> scheduler.reconcile()).isInstanceOf(IllegalStateException.class);
		assertThat(ReflectionTestUtils.getField(scheduler, "lastSucceededAt")).isNull();

		// watermark가 안 움직였으므로 다음 tick도 여전히 전체 재조정을 시도해야 한다.
		scheduler.reconcile();
		then(productReindexService).should(org.mockito.Mockito.times(2)).reconcileAll();
	}

	@Test
	@DisplayName("fullSweep이 성공하면 watermark를 전진시킨다")
	void fullSweep_succeeds_advancesWatermark() {
		scheduler = newScheduler();
		given(productReindexService.reconcileAll()).willReturn(true);

		scheduler.fullSweep();

		assertThat(ReflectionTestUtils.getField(scheduler, "lastSucceededAt")).isNotNull();
	}

	@Test
	@DisplayName("fullSweep이 실패(false)하면 watermark를 전진시키지 않는다")
	void fullSweep_returnsFalse_doesNotAdvanceWatermark() {
		scheduler = newScheduler();
		given(productReindexService.reconcileAll()).willReturn(false);

		scheduler.fullSweep();

		assertThat(ReflectionTestUtils.getField(scheduler, "lastSucceededAt")).isNull();
	}
}
