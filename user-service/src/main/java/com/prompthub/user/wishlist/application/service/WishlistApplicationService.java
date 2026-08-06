package com.prompthub.user.wishlist.application.service;

import com.prompthub.user.wishlist.application.dto.AddWishlistCommand;
import com.prompthub.user.wishlist.application.dto.AddWishlistResult;
import com.prompthub.user.wishlist.application.dto.WishlistItemResult;
import com.prompthub.user.wishlist.application.usecase.WishlistUseCase;
import com.prompthub.user.wishlist.domain.exception.WishlistDuplicatedException;
import com.prompthub.user.wishlist.domain.exception.WishlistForbiddenException;
import com.prompthub.user.wishlist.domain.exception.WishlistNotFoundException;
import com.prompthub.user.wishlist.domain.model.Wishlist;
import com.prompthub.user.wishlist.domain.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistApplicationService implements WishlistUseCase {

    private final WishlistRepository wishlistRepository;

    @Override
    @Transactional
    public AddWishlistResult add(AddWishlistCommand command) {
        if (wishlistRepository.existsByUserIdAndProductId(command.userId(), command.productId())) {
            throw new WishlistDuplicatedException();
        }
        Wishlist saved = wishlistRepository.save(Wishlist.create(command.userId(), command.productId()));
        return AddWishlistResult.from(saved);
    }

    @Override
    @Transactional
    public void remove(UUID wishlistId, UUID userId) {
        Wishlist wishlist = wishlistRepository.findById(wishlistId)
                .orElseThrow(WishlistNotFoundException::new);
        if (!wishlist.getUserId().equals(userId)) {
            throw new WishlistForbiddenException();
        }
        wishlistRepository.delete(wishlist);
    }

    @Override
    public List<WishlistItemResult> getWishlists(UUID userId, int page, int size) {
        return wishlistRepository.findByUserId(userId, page, size).stream()
                .map(WishlistItemResult::from)
                .toList();
    }

    @Override
    public long countWishlists(UUID userId) {
        return wishlistRepository.countByUserId(userId);
    }

    @Override
    public boolean exists(UUID userId, UUID productId) {
        return wishlistRepository.existsByUserIdAndProductId(userId, productId);
    }

}
