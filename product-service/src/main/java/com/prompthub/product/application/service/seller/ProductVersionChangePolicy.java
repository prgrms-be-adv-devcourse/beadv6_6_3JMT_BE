package com.prompthub.product.application.service.seller;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductVersionType;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * ON_SALE 상품 수정 요청이 실제로 새 버전을 만들 자격이 있는지 판정한다
 * 콘텐츠 자체가 MAJOR/PATCH 중 무엇에 해당하는지는 도메인
 * 메서드({@link Product#determineVersionType})가 이미 판단한다 — 여기서는 그 결과에 요청 맥락
 * (changeReason 유무, family에 이미 대기 중인 MAJOR가 있는지)을 얹어 진행 가능 여부만 정한다.
 * "이 변경을 승인해도 되는가"라는 한 가지 이유로만 바뀐다.
 */
@Service
public class ProductVersionChangePolicy {

	/** 반환값이 비어 있으면(no-op) 새 row를 만들지 않는다 — 호출자는 현재 ON_SALE을 그대로 돌려주면 된다. */
	public Optional<ProductVersionType> decideVersionChange(
		Product onSale, ProductContent candidate, ProductFamily family, String changeReason
	) {
		Optional<ProductVersionType> versionType = onSale.determineVersionType(candidate);
		if (versionType.isEmpty()) {
			return Optional.empty();
		}
		if (changeReason == null || changeReason.isBlank()) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
		}
		if (versionType.get() == ProductVersionType.MAJOR && family.pendingReview().isPresent()) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}
		return versionType;
	}
}
