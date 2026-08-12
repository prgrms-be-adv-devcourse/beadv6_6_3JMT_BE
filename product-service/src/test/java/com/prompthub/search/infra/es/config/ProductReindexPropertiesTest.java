package com.prompthub.search.infra.es.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductReindexPropertiesTest {

	@Test
	void bulkChunkSize가_0이면_생성을_거부한다() {
		assertThatThrownBy(() -> new ProductReindexProperties(0, 1000))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bulk-chunk-size");
	}

	@Test
	void bulkChunkSize가_음수면_생성을_거부한다() {
		assertThatThrownBy(() -> new ProductReindexProperties(-1, 1000))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bulk-chunk-size");
	}

	@Test
	void orphanScanPageSize가_0이면_생성을_거부한다() {
		assertThatThrownBy(() -> new ProductReindexProperties(500, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("orphan-scan-page-size");
	}

	@Test
	void 양수값은_그대로_허용한다() {
		assertThatCode(() -> new ProductReindexProperties(500, 1000)).doesNotThrowAnyException();
	}
}
