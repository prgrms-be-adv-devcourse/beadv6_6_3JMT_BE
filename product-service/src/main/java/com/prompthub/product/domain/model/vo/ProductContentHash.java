package com.prompthub.product.domain.model.vo;

import com.prompthub.product.domain.model.enums.ProductType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 복제 판정용 상품 본문 해시.
 *
 * <p>같은 프롬프트를 띄어쓰기 하나만 바꿔 남의 상품으로 다시 올리는 것을 막기 위한 값이다.
 * 검수 주체가 "같은 해시를 가진 다른 판매자 상품"을 조회해 복제로 판정한다.
 *
 * <p><b>검색용 {@code EmbeddingSource}의 해시와 목적이 반대라 공유하지 않는다.</b>
 * 그쪽은 "이 상품 글이 바뀌었나"(같은 상품의 과거와 비교), 이쪽은 "같은 글이 이미 있나"
 * (다른 상품과 비교)를 묻는다. 제목만 바꾸고 본문은 그대로인 경우 임베딩 해시는 바뀌어야
 * 하고(벡터 재생성 필요) 이 해시는 안 바뀌어야 해서, 한 값이 둘을 동시에 만족할 수 없다.
 * 대상도 다르다 — 그쪽은 제목·태그·소개글까지 넣고 본문을 2,000자에서 자르지만, 이쪽은
 * 본문 전문만 본다.
 */
public final class ProductContentHash {

	private ProductContentHash() {
	}

	/**
	 * @return 본문 해시. PROMPT가 아니면 {@code null} — 본문이 없어 비교 대상이 아니다.
	 *     PPT·EXCEL은 파일, NOTION은 외부 링크라 제목·소개글만 남는데, 그건 "본문 복제"가
	 *     아니라 "상품 설명 복제"라 베낀 사람이 제목만 바꿔도 빠져나간다. 파일·링크 본문
	 *     비교는 텍스트 추출(#602) 이후에나 가능하다.
	 */
	public static String of(ProductContent content) {
		if (content.productType() != ProductType.PROMPT) {
			return null;
		}
		return sha256(normalize(content.content()));
	}

	/**
	 * 정규화 지점. 지금은 원문을 그대로 돌려준다.
	 *
	 * <p>공백 축약(연속 공백·개행·탭 → 하나)·앞뒤 trim·소문자화가 여기에 추가된다.
	 * <b>이 메서드만 채우면 모든 호출 지점이 자동으로 탄다</b> — 호출부는 손댈 필요가 없다.
	 *
	 * <p><b>주의:</b> 이 메서드를 고치면 이미 저장된 해시가 전부 옛 규칙 값이라 매칭되지
	 * 않는다. 전 상품 해시를 다시 계산해야 한다.
	 */
	private static String normalize(String text) {
		return text;
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256은 모든 JVM 구현이 제공해야 하는 알고리즘이라 실제로는 도달하지 않는다.
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}
}
