package com.prompthub.user.seller.domain.repository;

import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerRegisterRepository {
    Optional<SellerRegister> findById(UUID registerId);
    Optional<SellerRegister> findLatestByUserId(UUID userId);
    boolean existsByUserIdAndStatusIn(UUID userId, List<SellerRegisterStatus> statuses);
    SellerRegister save(SellerRegister sellerRegister);
    List<SellerRegister> findAll(SellerRegisterStatus status, int page, int size);
    long count(SellerRegisterStatus status);
}
