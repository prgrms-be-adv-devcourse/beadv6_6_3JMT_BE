package com.prompthub.search.infra.openai;

import com.prompthub.search.application.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Spring AI의 {@link EmbeddingModel}을 감싼다.
 *
 * <p>모델·API 키는 {@code spring.ai.openai.*} 설정으로 오토컨피그가 주입한다. HTTP 클라이언트나
 * 직렬화를 직접 다루지 않는다.
 *
 * <p>앱 레벨 재시도를 두지 않는다. 임베딩은 20초 주기 재조정 배치에서 채우고, 실패한 상품은
 * 해시가 저장되지 않아 다음 사이클에 다시 대상이 된다 — 주기 자체가 재시도다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiEmbeddingAdapter implements EmbeddingClient {

	private final EmbeddingModel embeddingModel;

	@Override
	public float[] embed(String text) {
		try {
			return embeddingModel.embed(text);
		} catch (RuntimeException e) {
			// 예외를 밖으로 던지지 않는다 — 상품 하나의 임베딩 실패가 재조정 배치 전체를
			// 멈추면 그 사이클의 다른 상품들도 색인되지 않는다.
			log.warn("임베딩 생성에 실패했습니다. 다음 재조정 사이클에 다시 시도합니다.", e);
			return null;
		}
	}
}
