package com.prompthub.product.application.service;

import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문에 담긴 그 버전(row)에 직접 salesCount를 반영한다. family의 currentOnSale로 우회하지
 * 않는 이유: 결제 이벤트가 처리되는 시점엔 이미 판매중단·버전 교체로 currentOnSale이 비어있거나
 * 실제 구매된 버전과 다른 row일 수 있어, 그 경로로는 카운트가 조용히 유실될 수 있다(구매 시점엔
 * 반드시 ON_SALE였던 row이므로 anchor row 자체는 항상 존재한다). ES 집계는 family 전체 합산
 * (sumSalesCountByFamilyRootId)이라 어느 멤버 row에 붙어있든 결과가 같다.
 */
@Service
@RequiredArgsConstructor
public class ProductSalesCountService {

	private final ProductRepository productRepository;

	@Transactional
	public void incrementSalesCount(List<UUID> productIds) {
		productRepository.findAllByIdIn(productIds).forEach(product -> {
			product.incrementSalesCount();
			productRepository.save(product);
		});
	}

	@Transactional
	public void decrementSalesCount(List<UUID> productIds) {
		productRepository.findAllByIdIn(productIds).forEach(product -> {
			product.decrementSalesCount();
			productRepository.save(product);
		});
	}
}
