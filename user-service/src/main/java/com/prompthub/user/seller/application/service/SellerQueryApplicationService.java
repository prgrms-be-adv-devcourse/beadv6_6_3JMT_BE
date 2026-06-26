package com.prompthub.user.seller.application.service;

import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SellerQueryApplicationService implements SellerQueryUseCase {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<SellerInfoResult> findSellers(List<String> sellerIds) {
        List<UUID> ids = sellerIds.stream()
                .map(UUID::fromString)
                .toList();

        return userRepository.findAllByIds(ids).stream()
                .map(user -> new SellerInfoResult(
                        user.getUserId().toString(),
                        user.getName(),
                        user.getProfileImageUrl() != null ? user.getProfileImageUrl() : "",
                        user.getStatus().name()
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SellerInfoResult findSeller(String sellerId) {
        User user = userRepository.findById(UUID.fromString(sellerId))
                .orElseThrow(UserNotFoundException::new);
        return new SellerInfoResult(
                user.getUserId().toString(),
                user.getName(),
                user.getProfileImageUrl() != null ? user.getProfileImageUrl() : "",
                user.getStatus().name()
        );
    }
}
