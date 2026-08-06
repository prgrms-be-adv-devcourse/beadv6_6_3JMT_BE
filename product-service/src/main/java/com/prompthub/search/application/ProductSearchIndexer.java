package com.prompthub.search.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProductSearchIndexer {

	void upsert(FamilyUpsertInput input);

	boolean indexExists();

	Set<UUID> findAllIndexedFamilyRootIds();

	void bulkReconcile(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete);
}
