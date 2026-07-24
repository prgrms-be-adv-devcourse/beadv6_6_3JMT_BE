package com.prompthub.search.infra.es;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * popular 정렬의 function_score 가중치(등급 A 노브 — yml 수정 + 재기동으로 조정).
 * 초기값은 2026-07-16-es-7-tuning-knobs-design.md의 추측값을 그대로 가져온다.
 */
@ConfigurationProperties(prefix = "prompthub.search.ranking")
public record SearchRankingProperties(
	@DefaultValue("0.3") double salesWeight,
	@DefaultValue("0.1") double viewWeight,
	@DefaultValue("0.1") double ratingWeight,
	@DefaultValue("0.2") double freshnessWeight,
	@DefaultValue("30d") String freshnessScale,
	@DefaultValue("0.7") double freshnessDecay
) {
}
