package com.prompthub.user.user.infrastructure.persistence;

import com.prompthub.user.user.domain.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") UUID userId);

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :from AND u.createdAt < :to")
    long countByCreatedAtBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
