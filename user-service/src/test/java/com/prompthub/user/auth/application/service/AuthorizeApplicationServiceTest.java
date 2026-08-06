package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.AuthorizeResult;
import com.prompthub.user.auth.domain.exception.SessionInvalidatedException;
import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import com.prompthub.user.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AuthorizeApplicationServiceTest {

    @Mock
    private AuthorizationCacheRepository authorizationCacheRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private AuthorizeApplicationService authorizeApplicationService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final long CURRENT_EPOCH = 3L;

    private static RefreshToken refreshTokenAt(long epoch) {
        return RefreshToken.reconstruct(UUID.randomUUID(), USER_ID, "token", epoch, Instant.now().plusSeconds(60));
    }

    @Test
    void authorize_epoch_일치_캐시_hit_시_DB_조회없이_반환() {
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(refreshTokenAt(CURRENT_EPOCH)));
        given(authorizationCacheRepository.find(USER_ID))
                .willReturn(Optional.of(new AuthzSnapshot(UserStatus.ACTIVE, UserRole.SELLER)));

        AuthorizeResult result = authorizeApplicationService.authorize(USER_ID, CURRENT_EPOCH);

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.role()).isEqualTo(UserRole.SELLER);
        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    void authorize_캐시_miss_시_DB_조회_후_캐시_적재() {
        User user = User.create("테스트유저", "test@example.com", null, UserRole.BUYER, true);
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(refreshTokenAt(CURRENT_EPOCH)));
        given(authorizationCacheRepository.find(USER_ID)).willReturn(Optional.empty());
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        AuthorizeResult result = authorizeApplicationService.authorize(USER_ID, CURRENT_EPOCH);

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.role()).isEqualTo(UserRole.BUYER);
        then(authorizationCacheRepository).should()
                .save(USER_ID, new AuthzSnapshot(UserStatus.ACTIVE, UserRole.BUYER));
    }

    @Test
    void authorize_사용자_없으면_UserNotFoundException() {
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(refreshTokenAt(CURRENT_EPOCH)));
        given(authorizationCacheRepository.find(USER_ID)).willReturn(Optional.empty());
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authorizeApplicationService.authorize(USER_ID, CURRENT_EPOCH))
                .isInstanceOf(UserNotFoundException.class);

        then(authorizationCacheRepository).should(never()).save(any(UUID.class), any(AuthzSnapshot.class));
    }

    @Test
    void authorize_epoch_null이면_SessionInvalidatedException() {
        assertThatThrownBy(() -> authorizeApplicationService.authorize(USER_ID, null))
                .isInstanceOf(SessionInvalidatedException.class);

        then(refreshTokenRepository).shouldHaveNoInteractions();
        then(authorizationCacheRepository).shouldHaveNoInteractions();
    }

    @Test
    void authorize_세션_없으면_SessionInvalidatedException() {
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authorizeApplicationService.authorize(USER_ID, CURRENT_EPOCH))
                .isInstanceOf(SessionInvalidatedException.class);

        then(authorizationCacheRepository).shouldHaveNoInteractions();
    }

    @Test
    void authorize_epoch_불일치면_SessionInvalidatedException() {
        given(refreshTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(refreshTokenAt(CURRENT_EPOCH)));

        assertThatThrownBy(() -> authorizeApplicationService.authorize(USER_ID, CURRENT_EPOCH - 1))
                .isInstanceOf(SessionInvalidatedException.class);

        then(authorizationCacheRepository).shouldHaveNoInteractions();
    }
}
