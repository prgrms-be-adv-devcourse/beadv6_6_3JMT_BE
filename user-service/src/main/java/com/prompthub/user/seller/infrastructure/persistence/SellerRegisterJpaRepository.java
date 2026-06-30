package com.prompthub.user.seller.infrastructure.persistence;

import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerRegisterJpaRepository extends JpaRepository<SellerRegister, UUID> {
    Optional<SellerRegister> findTopByUserIdOrderBySubmittedAtDesc(UUID userId);
    boolean existsByUserIdAndStatusIn(UUID userId, List<SellerRegisterStatus> statuses);
    Page<SellerRegister> findAllByStatus(SellerRegisterStatus status, Pageable pageable);
    long countByStatus(SellerRegisterStatus status);
}
