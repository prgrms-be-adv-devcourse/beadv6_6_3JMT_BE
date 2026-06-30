package com.prompthub.user.seller.application.service;

import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.repository.UserRepository;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SellerQueryApplicationServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SellerQueryApplicationService sellerQueryApplicationService;

    private User createSeller(String name, String profileImageUrl) {
        return User.create(name, name.toLowerCase() + "@example.com", profileImageUrl, UserRole.SELLER, true);
    }

    @Test
    void findSellers_정상_조회_SellerInfoResult_목록_반환() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        User seller1 = createSeller("판매자A", "https://cdn.example.com/a.jpg");
        User seller2 = createSeller("판매자B", "https://cdn.example.com/b.jpg");
        given(userRepository.findAllByIds(List.of(id1, id2))).willReturn(List.of(seller1, seller2));

        List<SellerInfoResult> results = sellerQueryApplicationService.findSellers(
                List.of(id1.toString(), id2.toString()));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(SellerInfoResult::sellerName)
                .containsExactlyInAnyOrder("판매자A", "판매자B");
    }

    @Test
    void findSellers_profileImageUrl_null이면_빈_문자열_반환() {
        UUID id = UUID.randomUUID();
        User seller = createSeller("판매자A", null);
        given(userRepository.findAllByIds(List.of(id))).willReturn(List.of(seller));

        List<SellerInfoResult> results = sellerQueryApplicationService.findSellers(List.of(id.toString()));

        assertThat(results.get(0).profileImageUrl()).isEqualTo("");
    }

    @Test
    void findSellers_profileImageUrl_있으면_그대로_반환() {
        UUID id = UUID.randomUUID();
        String imageUrl = "https://cdn.example.com/profile.jpg";
        User seller = createSeller("판매자A", imageUrl);
        given(userRepository.findAllByIds(List.of(id))).willReturn(List.of(seller));

        List<SellerInfoResult> results = sellerQueryApplicationService.findSellers(List.of(id.toString()));

        assertThat(results.get(0).profileImageUrl()).isEqualTo(imageUrl);
    }

    @Test
    void findSellers_존재하지_않는_ID는_결과에서_생략() {
        UUID existingId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        User seller = createSeller("판매자A", null);
        given(userRepository.findAllByIds(List.of(existingId, missingId))).willReturn(List.of(seller));

        List<SellerInfoResult> results = sellerQueryApplicationService.findSellers(
                List.of(existingId.toString(), missingId.toString()));

        assertThat(results).hasSize(1);
    }

    @Test
    void findSellers_빈_ID_목록이면_빈_결과_반환() {
        given(userRepository.findAllByIds(List.of())).willReturn(List.of());

        List<SellerInfoResult> results = sellerQueryApplicationService.findSellers(List.of());

        assertThat(results).isEmpty();
    }

    @Test
    void findSellers_status_User_status_name으로_변환() {
        UUID id = UUID.randomUUID();
        User seller = createSeller("판매자A", null);
        given(userRepository.findAllByIds(List.of(id))).willReturn(List.of(seller));

        List<SellerInfoResult> results = sellerQueryApplicationService.findSellers(List.of(id.toString()));

        assertThat(results.get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    void findSellers_sellerId_조회된_User의_userId로_설정() {
        UUID id = UUID.randomUUID();
        User seller = createSeller("판매자A", null);
        given(userRepository.findAllByIds(List.of(id))).willReturn(List.of(seller));

        List<SellerInfoResult> results = sellerQueryApplicationService.findSellers(List.of(id.toString()));

        assertThat(results.get(0).sellerId()).isEqualTo(seller.getUserId().toString());
    }

    @Test
    void findSellers_UUID_문자열을_변환해_userRepository_호출() {
        UUID id = UUID.randomUUID();
        given(userRepository.findAllByIds(List.of(id))).willReturn(List.of());

        sellerQueryApplicationService.findSellers(List.of(id.toString()));

        then(userRepository).should().findAllByIds(List.of(id));
    }

    @Test
    void findSeller_정상_조회_SellerInfoResult_반환() {
        UUID id = UUID.randomUUID();
        User seller = createSeller("판매자A", "https://cdn.example.com/a.jpg");
        given(userRepository.findById(id)).willReturn(Optional.of(seller));

        SellerInfoResult result = sellerQueryApplicationService.findSeller(id.toString());

        assertThat(result.sellerName()).isEqualTo("판매자A");
        assertThat(result.profileImageUrl()).isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    void findSeller_존재하지_않는_ID_UserNotFoundException() {
        UUID id = UUID.randomUUID();
        given(userRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> sellerQueryApplicationService.findSeller(id.toString()))
                .isInstanceOf(UserNotFoundException.class);
    }
}
