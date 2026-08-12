package com.prompthub.search.infra.es.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * ES 재조정 bulk·고아 문서 스캔 튜닝값. yml 수정 + 재기동으로 조정한다.
 */
@ConfigurationProperties(prefix = "prompthub.search.reconcile")
public record ProductReindexProperties(
	@DefaultValue("500") int bulkChunkSize,
	@DefaultValue("1000") int orphanScanPageSize
) {

	// 0 이하로 잘못 설정되면 bulk chunking은 start가 전진하지 않는 무한 루프, 고아 스캔은
	// PIT 페이지 순회가 깨진다 — 외부 설정값이라 기동 시점에 막는다.
	public ProductReindexProperties {
		if (bulkChunkSize <= 0) {
			throw new IllegalArgumentException(
				"prompthub.search.reconcile.bulk-chunk-size는 1 이상이어야 합니다. bulkChunkSize=" + bulkChunkSize);
		}
		if (orphanScanPageSize <= 0) {
			throw new IllegalArgumentException(
				"prompthub.search.reconcile.orphan-scan-page-size는 1 이상이어야 합니다. orphanScanPageSize=" + orphanScanPageSize);
		}
	}
}
