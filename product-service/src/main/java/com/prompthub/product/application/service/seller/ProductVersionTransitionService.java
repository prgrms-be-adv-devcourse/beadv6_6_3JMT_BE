package com.prompthub.product.application.service.seller;

import com.prompthub.product.application.service.inspection.ProductInspectionRequestPublisher;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductVersionType;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ON_SALE 상품의 새 버전 row를 만들어 반영한다(2026-08-05 로드맵 PR4, ProductSellerService
 * 책임 분리). PATCH는 기존 row를 SUPERSEDED로 교대시키고, MAJOR는 기존 row를 그대로 두고 검수
 * 요청을 발행한다. "새 버전을 어떻게 반영하는가"라는 한 가지 이유로만 바뀐다 — 어떤 버전
 * 유형인지 판정하는 일은 {@link ProductVersionChangePolicy}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class ProductVersionTransitionService {

	private final ProductRepository productRepository;
	private final ProductInspectionRequestPublisher productInspectionRequestPublisher;

	public Product transitionToNextVersion(
		Product onSale, UUID nextProductId, ProductVersionType versionType,
		ProductContent stored, String changeReason
	) {
		Product next = onSale.createNextVersion(nextProductId, versionType, stored, changeReason);

		if (versionType == ProductVersionType.PATCH) {
			onSale.supersede();
			productRepository.save(onSale);
			productRepository.save(next);
		} else {
			productRepository.save(next);
			productInspectionRequestPublisher.publish(next);
		}
		return next;
	}
}
