package com.prompthub.product.domain.model.vo;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.List;
import lombok.Builder;

/**
 * 상품 내용 파라미터 객체 (Introduce Parameter Object, #405).
 * 생성 시 유형별 필수 필드 검증과 컬렉션 null 정규화를 수행하므로
 * 잘못된 조합의 인스턴스는 존재할 수 없다.
 */
@Builder
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
		imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
		tags = tags == null ? List.of() : List.copyOf(tags);
		if (!productType.isValidContentCombination(content, fileUrl, externalUrl)) {
			throw new ProductException(ProductErrorCode.PRODUCT_TYPE_FIELD_MISMATCH);
		}
	}
}
