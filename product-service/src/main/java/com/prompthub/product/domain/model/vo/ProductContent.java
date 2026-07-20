package com.prompthub.product.domain.model.vo;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * 상품 내용 파라미터 객체 (Introduce Parameter Object, #405).
 * 생성 시 유형별 필수 필드 검증과 컬렉션 null 정규화를 수행하므로
 * 잘못된 조합의 인스턴스는 존재할 수 없다.
 */
public record ProductContent(
	ProductType productType,
	String name,
	String description,
	String model,
	AmountType amountType,
	int amount,
	String thumbnailUrl,
	List<String> imageUrls,
	String content,
	String fileUrl,
	String externalUrl,
	List<String> tags
) {

	public ProductContent {
		imageUrls = imageUrls != null ? imageUrls : new ArrayList<>();
		tags = tags != null ? tags : new ArrayList<>();
		validateTypeFields(productType, content, fileUrl, externalUrl);
	}

	private static void validateTypeFields(
		ProductType productType, String content, String fileUrl, String externalUrl
	) {
		boolean hasContent = content != null && !content.isBlank();
		boolean hasFileUrl = fileUrl != null && !fileUrl.isBlank();
		boolean hasExternalUrl = externalUrl != null && !externalUrl.isBlank();

		boolean ok = switch (productType) {
			case PROMPT -> hasContent && !hasFileUrl && !hasExternalUrl;
			case PPT, EXCEL -> hasFileUrl && !hasContent && !hasExternalUrl;
			case NOTION -> hasExternalUrl && !hasContent && !hasFileUrl;
		};
		if (!ok) {
			throw new ProductException(ProductErrorCode.PRODUCT_TYPE_FIELD_MISMATCH);
		}
	}
}
