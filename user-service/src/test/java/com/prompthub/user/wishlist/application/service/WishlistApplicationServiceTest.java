package com.prompthub.user.wishlist.application.service;

import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.repository.UserRepository;
import com.prompthub.user.wishlist.application.client.ProductClient;
import com.prompthub.user.wishlist.application.dto.AddWishlistCommand;
import com.prompthub.user.wishlist.application.dto.AddWishlistResult;
import com.prompthub.user.wishlist.application.dto.ProductSummaryDto;
import com.prompthub.user.wishlist.application.dto.WishlistItemResult;
import com.prompthub.user.wishlist.domain.exception.WishlistDuplicatedException;
import com.prompthub.user.wishlist.domain.exception.WishlistForbiddenException;
import com.prompthub.user.wishlist.domain.exception.WishlistNotFoundException;
import com.prompthub.user.wishlist.domain.model.Wishlist;
import com.prompthub.user.wishlist.domain.repository.WishlistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class WishlistApplicationServiceTest {

    @Mock
    private WishlistRepository wishlistRepository;

    @Mock
    private ProductClient productClient;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WishlistApplicationService wishlistApplicationService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();

    private ProductSummaryDto productSummary() {
        return new ProductSummaryDto(
                PRODUCT_ID, SELLER_ID, "테스트 상품", 9900L,
                "https://cdn.example.com/thumb.jpg", "marketing", "GPT-4",
                100L, 4.5, "ACTIVE"
        );
    }

    private User seller() {
        return User.create("판매자A", "seller@example.com", null, UserRole.SELLER, true);
    }

    @Test
    void add_정상_찜_등록_결과_반환() {
        given(wishlistRepository.existsByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(false);
        given(wishlistRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AddWishlistResult result = wishlistApplicationService.add(new AddWishlistCommand(USER_ID, PRODUCT_ID));

        assertThat(result.wishlistId()).isNotNull();
        assertThat(result.productId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void add_이미_찜한_상품이면_WishlistDuplicatedException() {
        given(wishlistRepository.existsByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(true);

        assertThatThrownBy(() -> wishlistApplicationService.add(new AddWishlistCommand(USER_ID, PRODUCT_ID)))
                .isInstanceOf(WishlistDuplicatedException.class);

        then(wishlistRepository).should().existsByUserIdAndProductId(USER_ID, PRODUCT_ID);
        then(wishlistRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    void remove_본인_찜이면_정상_삭제() {
        Wishlist wishlist = Wishlist.create(USER_ID, PRODUCT_ID);
        UUID wishlistId = wishlist.getWishlistId();
        given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

        wishlistApplicationService.remove(wishlistId, USER_ID);

        then(wishlistRepository).should().delete(wishlist);
    }

    @Test
    void remove_찜_없으면_WishlistNotFoundException() {
        UUID wishlistId = UUID.randomUUID();
        given(wishlistRepository.findById(wishlistId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> wishlistApplicationService.remove(wishlistId, USER_ID))
                .isInstanceOf(WishlistNotFoundException.class);
    }

    @Test
    void remove_타인_찜이면_WishlistForbiddenException() {
        UUID otherUserId = UUID.randomUUID();
        Wishlist wishlist = Wishlist.create(otherUserId, PRODUCT_ID);
        UUID wishlistId = wishlist.getWishlistId();
        given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

        assertThatThrownBy(() -> wishlistApplicationService.remove(wishlistId, USER_ID))
                .isInstanceOf(WishlistForbiddenException.class);
    }

    @Test
    void getWishlists_상품_정보_포함해_결과_반환() {
        Wishlist wishlist = Wishlist.create(USER_ID, PRODUCT_ID);
        User sellerUser = seller();
        given(wishlistRepository.findByUserId(USER_ID, 0, 20)).willReturn(List.of(wishlist));
        given(productClient.getProductsByIds(List.of(PRODUCT_ID))).willReturn(List.of(productSummary()));
        given(userRepository.findAllByIds(List.of(SELLER_ID))).willReturn(List.of(sellerUser));

        List<WishlistItemResult> results = wishlistApplicationService.getWishlists(USER_ID, 0, 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("테스트 상품");
        assertThat(results.get(0).price()).isEqualTo(9900L);
    }

    @Test
    void getWishlists_product_service_응답_없으면_fallback_반환() {
        Wishlist wishlist = Wishlist.create(USER_ID, PRODUCT_ID);
        given(wishlistRepository.findByUserId(USER_ID, 0, 20)).willReturn(List.of(wishlist));
        given(productClient.getProductsByIds(any())).willReturn(List.of());
        given(userRepository.findAllByIds(List.of())).willReturn(List.of());

        List<WishlistItemResult> results = wishlistApplicationService.getWishlists(USER_ID, 0, 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("상품 정보 없음");
        assertThat(results.get(0).price()).isEqualTo(0L);
    }

    @Test
    void getWishlists_빈_목록이면_빈_결과_반환() {
        given(wishlistRepository.findByUserId(USER_ID, 0, 20)).willReturn(List.of());

        List<WishlistItemResult> results = wishlistApplicationService.getWishlists(USER_ID, 0, 20);

        assertThat(results).isEmpty();
        then(productClient).shouldHaveNoInteractions();
    }

    @Test
    void exists_찜_존재하면_true_반환() {
        given(wishlistRepository.existsByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(true);

        boolean result = wishlistApplicationService.exists(USER_ID, PRODUCT_ID);

        assertThat(result).isTrue();
    }

    @Test
    void exists_찜_없으면_false_반환() {
        given(wishlistRepository.existsByUserIdAndProductId(USER_ID, PRODUCT_ID)).willReturn(false);

        boolean result = wishlistApplicationService.exists(USER_ID, PRODUCT_ID);

        assertThat(result).isFalse();
    }
}
