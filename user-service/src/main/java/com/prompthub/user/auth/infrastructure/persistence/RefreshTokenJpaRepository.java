package com.prompthub.user.auth.infrastructure.persistence;

import com.prompthub.user.auth.domain.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByUserId(UUID userId);

    // 메서드명 파생 쿼리로 두면 Spring Data가 "ForUpdate"를 술어 키워드로 잘못
    // 파싱해 PropertyReferenceException이 난다("No property 'forUpdate' found") —
    // 그래서 @Query로 명시한다. 락은 @Lock으로 건다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.userId = :userId")
    Optional<RefreshToken> findByUserIdForUpdate(@Param("userId") UUID userId);

    void deleteByUserId(UUID userId);
}
