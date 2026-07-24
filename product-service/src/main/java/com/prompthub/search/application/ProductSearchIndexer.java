package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProductSearchIndexer {

	void upsert(Product onSale, long familySalesCount, long familyViewCount, double averageRating, LocalDateTime firstPublishedAt);

	boolean indexExists();

	Set<UUID> findAllIndexedFamilyRootIds();

	void bulkReconcile(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete);
}
