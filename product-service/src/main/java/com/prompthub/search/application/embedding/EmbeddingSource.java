package com.prompthub.search.application.embedding;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 상품 임베딩의 원문과 그 해시.
 *
 * <p>해시는 "텍스트가 그대로면 OpenAI를 다시 부르지 않는다"를 위해 있다. 재조정 배치는
 * {@code updated_at} 기준으로 대상을 고르는데, 조회수 증가나 판매수 변경만으로도 그 값이
 * 바뀐다. 해시가 없으면 상품을 한 번 열어보기만 해도 임베딩을 다시 만들게 된다.
 *
 * <p>상품이 무엇인지 설명하는 제목·태그·소개글을 사용하고 PROMPT만 모델명을 추가한다.
 * 본문과 파일 내용은 예시 문구로 인한 검색 오탐을 막기 위해 포함하지 않는다.
 */
public final class EmbeddingSource {

	private static final String DELIMITER = "\n";

	private final String text;

	private EmbeddingSource(String text) {
		this.text = text;
	}

	public static EmbeddingSource of(Product product) {
		List<String> parts = new ArrayList<>();
		addIfPresent(parts, product.getName());
		addIfPresent(parts, joinTags(product.getTags()));
		addIfPresent(parts, product.getDescription());
		if (product.getProductType() == ProductType.PROMPT) {
			addIfPresent(parts, product.getModel());
		}
		return new EmbeddingSource(String.join(DELIMITER, parts));
	}

	public String text() {
		return text;
	}

	/** SHA-256 hex 64자 — {@code product.embedding_source_hash VARCHAR(64)}에 맞춘다. */
	public String hash() {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256은 모든 JVM 구현이 제공해야 하는 알고리즘이라 실제로는 도달하지 않는다.
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}

	/** 비어 있는 파트는 빈 줄을 남기지 않고 아예 뺀다. */
	private static void addIfPresent(List<String> parts, String value) {
		if (value != null && !value.isBlank()) {
			parts.add(value);
		}
	}

	private static String joinTags(List<String> tags) {
		return tags == null ? null : String.join(" ", tags);
	}

}
