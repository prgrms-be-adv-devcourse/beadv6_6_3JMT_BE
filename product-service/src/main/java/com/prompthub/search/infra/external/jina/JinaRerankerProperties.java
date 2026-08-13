package com.prompthub.search.infra.external.jina;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "prompthub.search.reranker")
public record JinaRerankerProperties(
	@DefaultValue("https://api.jina.ai/v1/rerank") URI uri,
	@DefaultValue("") String apiKey,
	@DefaultValue("jina-reranker-v3") String model,
	@DefaultValue("0.05") double minScore,
	@DefaultValue("2s") Duration timeout
) {
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}
}
