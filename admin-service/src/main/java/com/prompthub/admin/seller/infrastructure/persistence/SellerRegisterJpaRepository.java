package com.prompthub.admin.seller.infrastructure.persistence;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SellerRegisterJpaRepository extends JpaRepository<SellerRegister, UUID> {
	Page<SellerRegister> findAllByStatus(SellerRegisterStatus status, Pageable pageable);
	long countByStatus(SellerRegisterStatus status);
}
