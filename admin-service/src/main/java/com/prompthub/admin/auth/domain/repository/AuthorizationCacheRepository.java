package com.prompthub.admin.auth.domain.repository;

import java.util.UUID;

public interface AuthorizationCacheRepository {
	void evict(UUID userId);
}
