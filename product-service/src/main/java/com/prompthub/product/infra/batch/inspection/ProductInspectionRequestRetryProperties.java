package com.prompthub.product.infra.batch.inspection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 오래 대기한 검수 요청 1회 재발행 기준(2026-08-05 로드맵 PR4). staleAfter는 정상 AI
 * 검수 소요시간보다 충분히 크게 둬야 중복 검수를 피한다 — 실제 값은 운영 지연 분포를 보고 조정한다.
 *
 * <p>재발행 주기(fixed-delay-ms) 자체는 여기 두지 않는다 — {@code @Scheduled}의
 * {@code fixedDelayString}은 SpEL로 같은 yml 키를 직접 읽으므로, 필드로 중복 선언하면
 * 실제로는 안 쓰이는 죽은 값이 된다({@code ProductReconcileScheduler}와 같은 관례).
 */
@ConfigurationProperties(prefix = "prompthub.product.inspection-request-retry")
public record ProductInspectionRequestRetryProperties(
	@DefaultValue("10m") Duration staleAfter,
	@DefaultValue("50") int batchSize
) {
}
