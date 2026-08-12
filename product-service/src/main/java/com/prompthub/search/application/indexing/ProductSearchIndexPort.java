package com.prompthub.search.application.indexing;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProductSearchIndexPort {

	boolean indexExists();

	Set<UUID> findAllIndexedFamilyRootIds();

	void bulkReconcile(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete);
}
