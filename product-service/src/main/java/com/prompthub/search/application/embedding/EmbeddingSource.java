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
 * <p>유형별로 넣을 수 있는 텍스트가 다르다 — PROMPT만 본문({@code content})이 있고
 * NOTION은 외부 링크, PPT·EXCEL은 파일이라 제목·태그·소개글이 전부다. 파일에서 텍스트를
 * 뽑는 것은 별도 이슈(#602)로 미뤄져 있다.
 */
public final class EmbeddingSource {

	/**
	 * 본문을 이 길이에서 자른다.
	 *
	 * <p>text-embedding-3-small의 입력 상한이 8,191토큰이라 그 아래로 두려는 것이지, 정확한
	 * 토큰 수를 계산하려는 게 아니다. 한글은 대략 글자당 1토큰 안팎이라 2,000자면 제목·태그·
	 * 소개글을 더해도 상한에 한참 못 미친다.
	 */
	public static final int MAX_CONTENT_CHARS = 2_000;

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
		addIfPresent(parts, truncate(product.getContent()));

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

	private static String truncate(String content) {
		if (content == null || content.length() <= MAX_CONTENT_CHARS) {
			return content;
		}
		return content.substring(0, MAX_CONTENT_CHARS);
	}
}
