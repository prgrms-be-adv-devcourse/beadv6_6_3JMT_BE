package com.prompthub.search.application.embedding;

/**
 * 텍스트를 벡터로 바꾼다.
 *
 * <p>{@link com.prompthub.search.application.query.ProductSearchQueryPort}·
 * {@link com.prompthub.search.application.indexing.ProductSearchIndexPort}와 같은 자리의 포트다 —
 * application이 벤더 타입(Spring AI)에 직접 의존하지 않게 하고, 테스트에서는 fake로 바꾼다.
 */
public interface EmbeddingClient {

	/**
	 * @return 임베딩 벡터. 호출에 실패하면 {@code null}을 반환한다 — 임베딩은 배치에서
	 *     채우는 부가 데이터라, 한 상품이 실패해도 나머지 색인을 멈추지 않는다.
	 *     실패한 상품은 해시가 저장되지 않으므로 다음 재조정 사이클에 다시 시도된다.
	 */
	float[] embed(String text);
}
