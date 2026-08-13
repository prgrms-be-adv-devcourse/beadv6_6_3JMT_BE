package com.prompthub.search.infra.external.jina;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.search.application.gateway.external.ProductRerankerGateway.RerankCandidate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JinaProductRerankerGatewayTest {

	private final HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);
	private final HttpResponse<String> response = mockResponse();

	@Test
	void 관련성_점수_미달_상품을_제외하고_PROMPT_모델을_입력에_포함한다() throws Exception {
		JinaProductRerankerGateway client = client("api-key", 0.5);
		RerankCandidate irrelevant = candidate("프로젝트 보고서", "PROMPT", "GPT-4o", "본문비밀값");
		RerankCandidate relevant = candidate("자기소개서 첨삭", "PROMPT", "GPT-5", "본문비밀값");
		given(response.statusCode()).willReturn(200);
		given(response.body()).willReturn("""
			{"results":[
			  {"index":1,"relevance_score":0.83},
			  {"index":0,"relevance_score":0.21}
			]}
			""");
		given(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).willReturn(response);

		Optional<List<UUID>> result = client.findRelevantProductIds("취업 자기소개서", List.of(irrelevant, relevant));

		assertThat(result).contains(List.of(relevant.productId()));
		ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
		verify(httpClient).send(requestCaptor.capture(), anyStringBodyHandler());
		String requestBody = requestCaptor.getValue().bodyPublisher().orElseThrow()
			.contentLength() > 0 ? requestBody(requestCaptor.getValue()) : "";
		assertThat(requestBody)
			.contains("GPT-4o", "GPT-5", "취업 자기소개서")
			.doesNotContain("본문비밀값");
	}

	@Test
	void API가_실패하면_BM25_폴백을_선택하도록_빈_결과를_반환한다() throws Exception {
		JinaProductRerankerGateway client = client("api-key", 0.5);
		given(response.statusCode()).willReturn(503);
		given(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).willReturn(response);

		assertThat(client.findRelevantProductIds("취업", List.of(candidate("상품", "PROMPT", "GPT-5", "본문"))))
			.isEmpty();
	}

	@Test
	void API_키가_없으면_외부_호출_없이_BM25_폴백을_선택한다() {
		JinaProductRerankerGateway client = client("", 0.5);

		assertThat(client.findRelevantProductIds("취업", List.of(candidate("상품", "PROMPT", "GPT-5", "본문"))))
			.isEmpty();
	}

	private JinaProductRerankerGateway client(String apiKey, double minScore) {
		JinaRerankerProperties properties = new JinaRerankerProperties(
			URI.create("https://api.jina.ai/v1/rerank"), apiKey, "jina-reranker-v3", minScore, Duration.ofSeconds(2));
		return new JinaProductRerankerGateway(properties, new ObjectMapper(), httpClient);
	}

	private RerankCandidate candidate(String name, String productType, String model, String content) {
		UUID id = UUID.randomUUID();
		return new RerankCandidate(id, name, List.of("태그"), "상품 소개", productType, model, true);
	}

	@Test
	void 추천_후보는_설명을_포함하고_모델을_제외한다() throws Exception {
		JinaProductRerankerGateway client = client("configured", 0.05);
		UUID productId = UUID.randomUUID();
		RerankCandidate candidate = new RerankCandidate(
			productId, "Docker 가이드", List.of("Docker"), "컨테이너 환경 구축", "PROMPT", "GPT-5", false);
		given(response.statusCode()).willReturn(200);
		given(response.body()).willReturn("{\"results\":[{\"index\":0,\"relevance_score\":0.1}]}");
		given(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).willReturn(response);

		assertThat(client.findRelevantProductIds("주식 분석", List.of(candidate))).contains(List.of(productId));
		ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
		verify(httpClient).send(requestCaptor.capture(), anyStringBodyHandler());
		assertThat(requestBody(requestCaptor.getValue()))
			.contains("Docker 가이드", "Docker", "컨테이너 환경 구축")
			.doesNotContain("GPT-5");
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<String> mockResponse() {
		return org.mockito.Mockito.mock(HttpResponse.class);
	}

	private HttpResponse.BodyHandler<String> anyStringBodyHandler() {
		return any();
	}

	private String requestBody(HttpRequest request) throws Exception {
		java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
		java.util.concurrent.CompletableFuture<Void> completed = new java.util.concurrent.CompletableFuture<>();
		java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> subscriber =
			new java.util.concurrent.Flow.Subscriber<>() {
				@Override
				public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}

				@Override
				public void onNext(java.nio.ByteBuffer item) {
					byte[] bytes = new byte[item.remaining()];
					item.get(bytes);
					output.writeBytes(bytes);
				}

				@Override
				public void onError(Throwable throwable) {
					completed.completeExceptionally(throwable);
				}

				@Override
				public void onComplete() {
					completed.complete(null);
				}
			};
		request.bodyPublisher().orElseThrow().subscribe(subscriber);
		completed.get();
		return output.toString(java.nio.charset.StandardCharsets.UTF_8);
	}
}
