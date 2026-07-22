package com.prompthub.product.search.application;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.UUID;

public interface ProductSearchIndexer {

	void upsert(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt);

	void delete(UUID familyRootId);

	void updatePrice(UUID familyRootId, int changedPrice);
}
