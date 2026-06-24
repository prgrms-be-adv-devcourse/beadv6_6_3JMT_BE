package com.prompthub.user.seller.infrastructure.persistence;

import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.seller.domain.repository.SellerRegisterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SellerRegisterRepositoryAdapter implements SellerRegisterRepository {

    private final SellerRegisterJpaRepository jpaRepository;

    @Override
    public Optional<SellerRegister> findLatestByUserId(UUID userId) {
        return jpaRepository.findTopByUserIdOrderBySubmittedAtDesc(userId);
    }

    @Override
    public boolean existsByUserIdAndStatusIn(UUID userId, List<SellerRegisterStatus> statuses) {
        return jpaRepository.existsByUserIdAndStatusIn(userId, statuses);
    }

    @Override
    public SellerRegister save(SellerRegister sellerRegister) {
        return jpaRepository.save(sellerRegister);
    }
}
