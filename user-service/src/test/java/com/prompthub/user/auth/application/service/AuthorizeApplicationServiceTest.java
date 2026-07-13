package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.dto.AuthorizeResult;
import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
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

    @InjectMocks
    private AuthorizeApplicationService authorizeApplicationService;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void authorize_캐시_hit_시_DB_조회없이_반환() {
        given(authorizationCacheRepository.find(USER_ID))
                .willReturn(Optional.of(new AuthzSnapshot(UserStatus.ACTIVE, UserRole.SELLER)));

        AuthorizeResult result = authorizeApplicationService.authorize(USER_ID);

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.role()).isEqualTo(UserRole.SELLER);
        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    void authorize_캐시_miss_시_DB_조회_후_캐시_적재() {
        User user = User.create("테스트유저", "test@example.com", null, UserRole.BUYER, true);
        given(authorizationCacheRepository.find(USER_ID)).willReturn(Optional.empty());
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        AuthorizeResult result = authorizeApplicationService.authorize(USER_ID);

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.role()).isEqualTo(UserRole.BUYER);
        then(authorizationCacheRepository).should()
                .save(USER_ID, new AuthzSnapshot(UserStatus.ACTIVE, UserRole.BUYER));
    }

    @Test
    void authorize_사용자_없으면_UserNotFoundException() {
        given(authorizationCacheRepository.find(USER_ID)).willReturn(Optional.empty());
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authorizeApplicationService.authorize(USER_ID))
                .isInstanceOf(UserNotFoundException.class);

        then(authorizationCacheRepository).should(never()).save(any(UUID.class), any(AuthzSnapshot.class));
    }
}
