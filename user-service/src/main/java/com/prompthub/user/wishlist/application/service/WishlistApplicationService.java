package com.prompthub.user.wishlist.application.service;

import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.repository.UserRepository;
import com.prompthub.user.wishlist.application.client.ProductClient;
import com.prompthub.user.wishlist.application.dto.AddWishlistCommand;
import com.prompthub.user.wishlist.application.dto.AddWishlistResult;
import com.prompthub.user.wishlist.application.dto.ProductSummaryDto;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistApplicationService implements WishlistUseCase {

    private static final String FALLBACK_TITLE = "상품 정보 없음";
    private static final String FALLBACK_SELLER = "알 수 없음";

    private final WishlistRepository wishlistRepository;
    private final ProductClient productClient;
    private final UserRepository userRepository;

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
        List<Wishlist> wishlists = wishlistRepository.findByUserId(userId, page, size);
        if (wishlists.isEmpty()) {
            return List.of();
        }

        List<UUID> productIds = wishlists.stream()
                .map(Wishlist::getProductId)
                .toList();

        Map<UUID, ProductSummaryDto> productMap = productClient.getProductsByIds(productIds)
                .stream()
                .collect(Collectors.toMap(ProductSummaryDto::productId, Function.identity()));

        List<UUID> sellerIds = productMap.values().stream()
                .map(ProductSummaryDto::sellerId)
                .distinct()
                .toList();

        Map<UUID, String> sellerNameMap = userRepository.findAllByIds(sellerIds)
                .stream()
                .collect(Collectors.toMap(User::getUserId, User::getName));

        return wishlists.stream()
                .map(wishlist -> toResult(wishlist, productMap, sellerNameMap))
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

    private WishlistItemResult toResult(
            Wishlist wishlist,
            Map<UUID, ProductSummaryDto> productMap,
            Map<UUID, String> sellerNameMap
    ) {
        ProductSummaryDto product = productMap.get(wishlist.getProductId());
        if (product == null) {
            return new WishlistItemResult(
                    wishlist.getWishlistId(),
                    wishlist.getProductId(),
                    FALLBACK_TITLE,
                    null,
                    0L,
                    FALLBACK_SELLER,
                    0.0,
                    0L,
                    null,
                    null,
                    wishlist.getCreatedAt()
            );
        }
        return new WishlistItemResult(
                wishlist.getWishlistId(),
                wishlist.getProductId(),
                product.title(),
                product.thumbnailUrl(),
                product.price(),
                sellerNameMap.getOrDefault(product.sellerId(), FALLBACK_SELLER),
                product.averageRating(),
                product.salesCount(),
                product.category(),
                product.model(),
                wishlist.getCreatedAt()
        );
    }
}
