package com.prompthub.product.application.gateway.external;

import java.util.List;

/**
 * 외부 객체 저장소(S3) 호출 포트. 3자 API 경계이므로 {@code Gateway} 접미사를 쓴다
 * (.claude/rules/clean-architecture.md §4). application 코드는 이 포트로만 저장소를
 * 다루고, AWS SDK·bucket 설정 같은 구현 세부는 infra 어댑터에 남는다.
 */
public interface ObjectStorageGateway {

	String createPresignedGetUrl(String key);

	String createPresignedPutUrl(String key, String contentType);

	void copy(String sourceKey, String destKey);

	void delete(String key);

	/** key가 없으면 presign 자체를 시도하지 않고 null을 돌려준다 — 여러 서비스에서 반복되던 null-safe 래퍼. */
	default String presignIfPresent(String key) {
		return (key == null || key.isBlank()) ? null : createPresignedGetUrl(key);
	}

	default List<String> presignAllIfPresent(List<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		return keys.stream().map(this::createPresignedGetUrl).toList();
	}
}
