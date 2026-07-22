package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductFamilyResolver {

	private final ProductRepository productRepository;

	public Map<UUID, Product> resolveFamilyRepresentatives(
		List<UUID> requestedIds,
		Function<ProductFamily, Optional<Product>> selector
	) {
		List<Product> anchors = productRepository.findAllByIdIn(requestedIds);
		Map<UUID, UUID> familyRootByRequestedId = anchors.stream()
			.collect(Collectors.toMap(Product::getId, Product::familyRootId));
		List<UUID> familyRootIds = familyRootByRequestedId.values().stream().distinct().toList();
		List<Product> allMembers = familyRootIds.isEmpty()
			? List.of()
			: productRepository.findAllByFamilyRootIds(familyRootIds);
		Map<UUID, List<Product>> membersByFamily = allMembers.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));

		Map<UUID, Product> result = new LinkedHashMap<>();
		for (UUID requestedId : requestedIds) {
			UUID familyRootId = familyRootByRequestedId.get(requestedId);
			if (familyRootId == null) {
				continue;
			}
			ProductFamily family = ProductFamily.of(familyRootId, membersByFamily.getOrDefault(familyRootId, List.of()));
			selector.apply(family).ifPresent(product -> result.put(requestedId, product));
		}
		return result;
	}
}
