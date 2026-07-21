package com.prompthub.admin.auth.domain.repository;

import java.util.UUID;

public interface RefreshTokenRepository {
	void deleteByUserId(UUID userId);
}
