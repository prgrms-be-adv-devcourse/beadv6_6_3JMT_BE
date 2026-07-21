package com.prompthub.admin.auth.infrastructure.persistence;

import com.prompthub.admin.auth.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, UUID> {
	void deleteByUserId(UUID userId);
}
