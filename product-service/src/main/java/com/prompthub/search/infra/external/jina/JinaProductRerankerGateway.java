package com.prompthub.search.infra.external.jina;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway.RerankCandidate;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JinaProductRerankerGateway implements ProductRerankerGateway {

	private static final String AUTHORIZATION = "Authorization";
	private static final String CONTENT_TYPE = "Content-Type";
	private static final String APPLICATION_JSON = "application/json";

	private final JinaRerankerProperties properties;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	@Autowired
	public JinaProductRerankerGateway(JinaRerankerProperties properties, ObjectMapper objectMapper) {
		this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
	}

	JinaProductRerankerGateway(JinaRerankerProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
	}

	/** 빈 Optional은 외부 재랭킹을 사용할 수 없어 BM25로 축소해야 한다는 뜻이다. */
	@Override
	public Optional<List<UUID>> findRelevantProductIds(String keyword, List<RerankCandidate> candidates) {
		if (!properties.isConfigured()) {
			return Optional.empty();
		}
		if (candidates.isEmpty()) {
			return Optional.of(List.of());
		}

		try {
			RerankRequest body = new RerankRequest(
				properties.model(), keyword, candidates.stream().map(this::buildRerankText).toList(), candidates.size());
			HttpRequest request = HttpRequest.newBuilder(properties.uri())
				.timeout(properties.timeout())
				.header(AUTHORIZATION, "Bearer " + properties.apiKey())
				.header(CONTENT_TYPE, APPLICATION_JSON)
				.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.warn("Jina reranker 호출 실패. status={}", response.statusCode());
				return Optional.empty();
			}
			return Optional.of(collectRelevantIds(objectMapper.readValue(response.body(), RerankResponse.class), candidates));
		} catch (IOException exception) {
			log.warn("Jina reranker 통신 실패. BM25 검색으로 축소합니다.", exception);
			return Optional.empty();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("Jina reranker 호출 중단. BM25 검색으로 축소합니다.");
			return Optional.empty();
		} catch (RuntimeException exception) {
			log.warn("Jina reranker 응답 처리 실패. BM25 검색으로 축소합니다.", exception);
			return Optional.empty();
		}
	}

	private List<UUID> collectRelevantIds(RerankResponse response, List<RerankCandidate> candidates) {
		List<UUID> relevantIds = new ArrayList<>();
		for (RerankResult result : response.results()) {
			if (result.relevanceScore() < properties.minScore()
				|| result.index() < 0
				|| result.index() >= candidates.size()) {
				continue;
			}
			relevantIds.add(candidates.get(result.index()).productId());
		}
		return relevantIds;
	}

	private String buildRerankText(RerankCandidate candidate) {
		List<String> parts = new ArrayList<>();
		addIfPresent(parts, candidate.name());
		if (candidate.tags() != null && !candidate.tags().isEmpty()) {
			parts.add(String.join(" ", candidate.tags()));
		}
		addIfPresent(parts, candidate.description());
		if (candidate.includeModel() && "PROMPT".equals(candidate.productType())) {
			addIfPresent(parts, candidate.model());
		}
		return String.join("\n", parts);
	}

	private void addIfPresent(List<String> parts, String value) {
		if (value != null && !value.isBlank()) {
			parts.add(value);
		}
	}

	private record RerankRequest(String model, String query, List<String> documents,
		@JsonProperty("top_n") int topN) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record RerankResponse(List<RerankResult> results) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record RerankResult(int index, @JsonProperty("relevance_score") double relevanceScore) {
	}
}
