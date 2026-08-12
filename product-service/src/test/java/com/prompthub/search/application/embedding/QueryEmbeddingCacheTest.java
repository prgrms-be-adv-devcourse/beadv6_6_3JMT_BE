package com.prompthub.search.application.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryEmbeddingCacheTest {

	@Test
	@DisplayName("같은 검색어를 두 번 물어도 임베딩은 한 번만 만든다")
	void cachesByKeyword() {
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		cache.get("노션 템플릿");
		cache.get("노션 템플릿");

		assertThat(client.calls).hasSize(1);
	}

	@Test
	@DisplayName("앞뒤 공백과 대소문자만 다른 검색어는 같은 항목으로 본다")
	void normalizesKeyword() {
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		cache.get("Notion Template");
		cache.get("  notion template  ");

		assertThat(client.calls).hasSize(1);
	}

	@Test
	@DisplayName("임베딩 입력도 캐시 키와 같은 정규화 문자열을 쓴다")
	void embedsNormalizedKeyword() {
		// #699 — 키만 소문자로 합치고 입력을 원문으로 두면, "GPT"와 "gpt"가 같은 키에 서로 다른
		// 벡터를 넣어 먼저 검색된 쪽이 이기는 비결정 동작이 된다.
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		cache.get("  GPT  ");

		assertThat(client.calls).containsExactly("gpt");
	}

	@Test
	@DisplayName("상한을 넘기면 가장 오래 쓰지 않은 항목이 밀려난다")
	void evictsLeastRecentlyUsed() {
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		for (int i = 0; i < QueryEmbeddingCache.MAX_ENTRIES; i++) {
			cache.get("검색어" + i);
		}
		// 0번을 다시 써서 최근 사용으로 만든 뒤, 새 항목을 넣어 축출을 유발한다.
		cache.get("검색어0");
		cache.get("새검색어");

		int callsBefore = client.calls.size();
		cache.get("검색어0");
		cache.get("검색어1");

		assertThat(client.calls.size() - callsBefore)
			.as("검색어0은 남아 있고 검색어1만 다시 만들어야 한다")
			.isEqualTo(1);
	}

	@Test
	@DisplayName("임베딩 생성이 실패하면 캐시에 담지 않아 다음에 다시 시도한다")
	void doesNotCacheFailure() {
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		client.returnNull = true;
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		assertThat(cache.get("검색어")).isNull();
		assertThat(cache.get("검색어")).isNull();

		assertThat(client.calls).hasSize(2);
	}

	@Test
	@DisplayName("예산 시간을 넘기면 null을 돌려줘 호출자가 lexical만으로 응답하게 한다")
	void returnsNullWhenSlowerThanBudget() {
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		client.delayMillis = QueryEmbeddingCache.BUDGET_MILLIS * 4;
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		long start = System.currentTimeMillis();
		float[] embedding = cache.get("느린검색어");
		long elapsed = System.currentTimeMillis() - start;

		assertThat(embedding).isNull();
		assertThat(elapsed)
			.as("예산을 넘겨 기다리지 않아야 한다")
			.isLessThan(client.delayMillis);
	}

	@Test
	@DisplayName("예산을 넘겨 포기한 호출도 끝나면 캐시에 담겨 다음 요청이 이득을 본다")
	void lateResultStillWarmsCache() throws InterruptedException {
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		client.delayMillis = QueryEmbeddingCache.BUDGET_MILLIS * 2;
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		assertThat(cache.get("느린검색어")).isNull();
		Thread.sleep(client.delayMillis * 2L);

		assertThat(cache.get("느린검색어")).isNotNull();
		assertThat(client.calls)
			.as("두 번째는 캐시에서 나와야 한다")
			.hasSize(1);
	}

	@Test
	@DisplayName("검색어가 비어 있으면 임베딩을 만들지 않는다")
	void skipsBlankKeyword() {
		RecordingEmbeddingClient client = new RecordingEmbeddingClient();
		QueryEmbeddingCache cache = new QueryEmbeddingCache(client);

		assertThat(cache.get("   ")).isNull();
		assertThat(cache.get(null)).isNull();

		assertThat(client.calls).isEmpty();
	}

	private static final class RecordingEmbeddingClient implements EmbeddingClient {

		private final List<String> calls = new CopyOnWriteArrayList<>();
		private boolean returnNull;
		private int delayMillis;

		@Override
		public float[] embed(String text) {
			calls.add(text);
			if (delayMillis > 0) {
				try {
					Thread.sleep(delayMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
			return returnNull ? null : new float[] {0.1f, 0.2f};
		}
	}
}
