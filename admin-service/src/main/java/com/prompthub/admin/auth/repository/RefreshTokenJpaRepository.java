package com.prompthub.admin.auth.repository;

import com.prompthub.admin.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, UUID> {
	void deleteByUserId(UUID userId);
}
