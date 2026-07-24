package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

	@Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :from AND u.createdAt < :to")
	long countByCreatedAtBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	@Query("""
		SELECT u.userId AS userId, u.name AS name, u.profileImageUrl AS profileImageUrl
		FROM User u
		WHERE u.userId IN :userIds
		""")
	List<UserProfileProjection> findProfilesByIds(@Param("userIds") List<UUID> userIds);

	interface UserProfileProjection {
		UUID getUserId();
		String getName();
		String getProfileImageUrl();
	}
}
