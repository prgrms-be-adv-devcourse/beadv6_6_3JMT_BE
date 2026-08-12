package com.prompthub.product.infra.batch;
import com.prompthub.product.infra.batch.inspection.ProductInspectionRequestRetryScheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.product.application.service.inspection.ProductInspectionRequestRetryService;
import com.prompthub.product.infra.batch.inspection.ProductInspectionRequestRetryProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 후보 조회·순회만 하는 스케줄러의 위임 흐름을 검증한다(2026-08-05 로드맵 PR4). */
@ExtendWith(MockitoExtension.class)
class ProductInspectionRequestRetrySchedulerTest {

	@Mock
	private ProductInspectionRequestRetryService productInspectionRequestRetryService;

	@Test
	@DisplayName("후보로 조회된 상품마다 재발행을 시도한다")
	void retryStaleInspectionRequests_retriesEachCandidate() {
		ProductInspectionRequestRetryProperties properties =
			new ProductInspectionRequestRetryProperties(Duration.ofMinutes(10), 50);
		ProductInspectionRequestRetryScheduler scheduler =
			new ProductInspectionRequestRetryScheduler(productInspectionRequestRetryService, properties);
		UUID productId1 = UUID.randomUUID();
		UUID productId2 = UUID.randomUUID();
		given(productInspectionRequestRetryService.findStaleInspectionRequestCandidateIds(any(), eq(50)))
			.willReturn(List.of(productId1, productId2));

		scheduler.retryStaleInspectionRequests();

		then(productInspectionRequestRetryService).should().retryStaleInspectionRequest(eq(productId1), any());
		then(productInspectionRequestRetryService).should().retryStaleInspectionRequest(eq(productId2), any());
	}

	@Test
	@DisplayName("후보가 없으면 재발행을 시도하지 않는다")
	void retryStaleInspectionRequests_noCandidates_doesNothing() {
		ProductInspectionRequestRetryProperties properties =
			new ProductInspectionRequestRetryProperties(Duration.ofMinutes(10), 50);
		ProductInspectionRequestRetryScheduler scheduler =
			new ProductInspectionRequestRetryScheduler(productInspectionRequestRetryService, properties);
		given(productInspectionRequestRetryService.findStaleInspectionRequestCandidateIds(any(), eq(50)))
			.willReturn(List.of());

		scheduler.retryStaleInspectionRequests();

		then(productInspectionRequestRetryService).should(org.mockito.Mockito.never())
			.retryStaleInspectionRequest(any(), any());
	}

	@Test
	@DisplayName("한 후보의 재발행이 실패해도 나머지 후보는 계속 시도한다")
	void retryStaleInspectionRequests_oneCandidateFails_stillTriesTheRest() {
		ProductInspectionRequestRetryProperties properties =
			new ProductInspectionRequestRetryProperties(Duration.ofMinutes(10), 50);
		ProductInspectionRequestRetryScheduler scheduler =
			new ProductInspectionRequestRetryScheduler(productInspectionRequestRetryService, properties);
		UUID failing = UUID.randomUUID();
		UUID next = UUID.randomUUID();
		given(productInspectionRequestRetryService.findStaleInspectionRequestCandidateIds(any(), eq(50)))
			.willReturn(List.of(failing, next));
		org.mockito.BDDMockito.willThrow(new RuntimeException("일시적 S3 오류"))
			.given(productInspectionRequestRetryService).retryStaleInspectionRequest(eq(failing), any());

		scheduler.retryStaleInspectionRequests();

		then(productInspectionRequestRetryService).should().retryStaleInspectionRequest(eq(next), any());
	}
}
