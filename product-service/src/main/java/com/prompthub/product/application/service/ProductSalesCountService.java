package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductSalesCountService {

	private final ProductRepository productRepository;

	@Transactional
	public void incrementSalesCount(List<UUID> productIds) {
		forEachFamily(productIds, family ->
			family.currentOnSale().ifPresent(onSale -> {
				onSale.incrementSalesCount();
				productRepository.save(onSale);
			}));
	}

	@Transactional
	public void decrementSalesCount(List<UUID> productIds) {
		forEachFamily(productIds, family ->
			decrementTarget(family).ifPresent(target -> {
				target.decrementSalesCount();
				productRepository.save(target);
			}));
	}

	// 판매수는 family 전체 버전 합산으로 노출하므로, 감소는 현재 ON_SALE에 우선 적용하되
	// ON_SALE 판매수가 0이면 family 내 판매수>0 버전에 적용해 family 합이 정확히 줄어들게 한다.
	private Optional<Product> decrementTarget(ProductFamily family) {
		Optional<Product> onSale = family.currentOnSale();
		if (onSale.isPresent() && onSale.get().getSalesCount() > 0) {
			return onSale;
		}
		return family.members().stream()
			.filter(member -> member.getSalesCount() > 0)
			.findFirst();
	}

	// 요청 id를 family의 현재 ON_SALE로 resolve하기 위해, 요청 row와 그 family 전체를 로드한다.
	private void forEachFamily(List<UUID> requestedIds, Consumer<ProductFamily> action) {
		List<Product> anchors = productRepository.findAllByIdIn(requestedIds);
		Map<UUID, UUID> familyRootByRequestedId = anchors.stream()
			.collect(Collectors.toMap(Product::getId, Product::familyRootId));
		List<UUID> familyRootIds = familyRootByRequestedId.values().stream().distinct().toList();
		Map<UUID, List<Product>> membersByFamily = familyRootIds.isEmpty()
			? Map.of()
			: productRepository.findAllByFamilyRootIds(familyRootIds).stream()
				.collect(Collectors.groupingBy(Product::familyRootId));

		for (UUID requestedId : requestedIds) {
			UUID familyRootId = familyRootByRequestedId.get(requestedId);
			if (familyRootId == null) {
				continue;
			}
			action.accept(ProductFamily.of(familyRootId, membersByFamily.getOrDefault(familyRootId, List.of())));
		}
	}
}
