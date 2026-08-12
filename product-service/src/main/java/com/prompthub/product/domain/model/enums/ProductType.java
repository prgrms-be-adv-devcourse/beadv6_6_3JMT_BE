package com.prompthub.product.domain.model.enums;

import com.prompthub.product.domain.model.vo.ProductDeliverable;
import com.prompthub.product.domain.model.vo.ProductDeliverable.Type;

public enum ProductType {
	PROMPT,
	NOTION,
	PPT,
	EXCEL;

	public boolean isValidContentCombination(String content, String fileObjectKey, String externalUrl) {
		boolean hasContent = isProvided(content);
		boolean hasFileObjectKey = isProvided(fileObjectKey);
		boolean hasExternalUrl = isProvided(externalUrl);

		return switch (this) {
			case PROMPT -> hasContent && !hasFileObjectKey && !hasExternalUrl;
			case PPT, EXCEL -> hasFileObjectKey && !hasContent && !hasExternalUrl;
			case NOTION -> hasExternalUrl && !hasContent && !hasFileObjectKey;
		};
	}

	public ProductDeliverable resolveDeliverable(String content, String fileObjectKey, String externalUrl) {
		// 도메인은 저장된 원본만 고른다. 파일 다운로드 URL 생성은 application의 객체 저장소 경계가 맡는다.
		return switch (this) {
			case PROMPT -> new ProductDeliverable(Type.INLINE_CONTENT, content);
			case PPT, EXCEL -> new ProductDeliverable(Type.FILE_OBJECT_KEY, fileObjectKey);
			case NOTION -> new ProductDeliverable(Type.EXTERNAL_URL, externalUrl);
		};
	}

	private boolean isProvided(String value) {
		return value != null && !value.isBlank();
	}
}
