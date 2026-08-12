package com.prompthub.search.application.embedding;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 검색어를 벡터로 바꿔 주되, 같은 검색어를 반복해서 외부 API로 보내지 않는다.
 *
 * <p>검색어는 소수가 반복된다("노션 템플릿" 같은 것). 매번 OpenAI를 부르면 응답이 느려지고
 * 돈이 나가므로 최근 것들을 메모리에 들고 있는다.
 *
 * <p>Redis를 쓰지 않는다 — 모든 서비스가 {@code replicas: 1}이라 인스턴스 간 공유 이득이
 * 없고, 캐시 미스 비용이 OpenAI 호출 한 번이다. 의존성·연결 설정·직렬화를 감당할 근거가
 * 없다. {@code replicas > 1}이 되면 그때 옮긴다.
 */
@Slf4j
@Component
public class QueryEmbeddingCache {

	/** 벡터 하나가 6KB 남짓이라 1000개면 약 6MB다. 검색어 다양성 대비 넉넉하다. */
	static final int MAX_ENTRIES = 1_000;

	/**
	 * 이 시간을 넘기면 이번 요청은 포기하고 글자 기반 결과만 준다.
	 *
	 * <p>검색은 사용자가 기다리는 화면이라, 외부 API가 느릴 때 그만큼 같이 느려지면 안 된다.
	 * 의미 검색은 있으면 좋은 것이지 없으면 검색이 안 되는 것이 아니다.
	 */
	static final int BUDGET_MILLIS = 300;

	private final EmbeddingClient embeddingClient;
	private final Map<String, float[]> cache;

	public QueryEmbeddingCache(EmbeddingClient embeddingClient) {
		this.embeddingClient = embeddingClient;
		this.cache = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
				return size() > MAX_ENTRIES;
			}
		};
	}

	/**
	 * @return 검색어 벡터. 검색어가 비었거나, 생성에 실패했거나, {@link #BUDGET_MILLIS}를
	 *     넘겼으면 {@code null} — 호출자는 글자 기반 결과만으로 응답한다
	 */
	public float[] get(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return null;
		}

		String key = normalize(keyword);
		float[] cached = lookup(key);
		if (cached != null) {
			return cached;
		}

		// 예산을 넘겨도 작업을 취소하지 않는다. 뒤늦게 끝나면 캐시에 담겨 다음 요청이 이득을 본다.
		//
		// 임베딩 입력은 원문이 아니라 캐시 키와 같은 정규화 문자열이다(#699). 키만 합치고 입력을
		// 원문으로 두면 "GPT"와 "gpt"가 같은 키에 다른 벡터를 넣어, 어느 쪽을 먼저 검색했느냐에
		// 따라 파드별로 결과가 달라진다. 임베딩은 대소문자에 민감하다(dev 실측: "GPT" 0.365 vs
		// "gpt" 0.341 — 하한 0.35를 사이에 두고 갈림).
		CompletableFuture<float[]> pending = CompletableFuture.supplyAsync(() -> {
			float[] embedding = embeddingClient.embed(key);
			if (embedding != null) {
				store(key, embedding);
			}
			return embedding;
		});

		try {
			return pending.get(BUDGET_MILLIS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.debug("쿼리 임베딩이 {}ms 예산을 넘겨 글자 기반 결과만 사용합니다. keyword={}", BUDGET_MILLIS, keyword);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			log.warn("쿼리 임베딩 생성에 실패해 글자 기반 결과만 사용합니다.", e);
			return null;
		}
	}

	/** 앞뒤 공백·대소문자만 다른 검색어가 캐시를 따로 차지하지 않게 한다. */
	private String normalize(String keyword) {
		return keyword.trim().toLowerCase(Locale.ROOT);
	}

	private synchronized float[] lookup(String key) {
		return cache.get(key);
	}

	private synchronized void store(String key, float[] embedding) {
		cache.put(key, embedding);
	}
}
