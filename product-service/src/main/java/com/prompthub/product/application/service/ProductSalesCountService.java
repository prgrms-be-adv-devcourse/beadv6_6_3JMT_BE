package com.prompthub.product.application.service;

import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
