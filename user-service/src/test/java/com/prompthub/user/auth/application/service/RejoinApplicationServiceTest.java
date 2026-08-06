package com.prompthub.user.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.prompthub.user.auth.application.dto.OAuthLoginCompletedResult;
import com.prompthub.user.auth.application.dto.RejoinCommand;
import com.prompthub.user.auth.domain.exception.InvalidRejoinTokenException;
import com.prompthub.user.auth.domain.repository.RejoinTokenRepository;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import com.prompthub.user.user.domain.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class RejoinApplicationServiceTest {

    @Mock
    private RejoinTokenRepository rejoinTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LoginSessionIssuer loginSessionIssuer;

    @InjectMocks
    private RejoinApplicationService rejoinApplicationService;

    @Test
    void rejoin_유효한_토큰이면_기존_사용자를_ACTIVE로_복구하고_세션을_발급한다() {
        User user = createWithdrawnUser();
        UUID originalUserId = user.getUserId();
        String originalEmail = user.getEmail();
        Set<UserRole> originalRoles = user.getRoles();
        OAuthLoginCompletedResult completedResult = completedResult(user);

        given(rejoinTokenRepository.consume("rejoin-token"))
                .willReturn(Optional.of(user.getUserId()));
        given(userRepository.findByIdForUpdate(user.getUserId()))
                .willReturn(Optional.of(user));
        given(loginSessionIssuer.issue(user, false)).willReturn(completedResult);

        OAuthLoginCompletedResult result =
                rejoinApplicationService.rejoin(new RejoinCommand("rejoin-token"));

        assertThat(result).isSameAs(completedResult);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getUserId()).isEqualTo(originalUserId);
        assertThat(user.getEmail()).isEqualTo(originalEmail);
        assertThat(user.getRoles()).containsExactlyInAnyOrderElementsOf(originalRoles);
    }

    @Test
    void rejoin_소비할_토큰이_없으면_A014로_거부한다() {
        given(rejoinTokenRepository.consume("rejoin-token")).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                rejoinApplicationService.rejoin(new RejoinCommand("rejoin-token")))
                .isInstanceOf(InvalidRejoinTokenException.class);

        then(userRepository).shouldHaveNoInteractions();
        then(loginSessionIssuer).shouldHaveNoInteractions();
    }

    @Test
    void rejoin_사용자가_WITHDRAWN이_아니면_A014로_거부한다() {
        User activeUser = User.create("사용자", "user@example.com", null, UserRole.BUYER, true);
        given(rejoinTokenRepository.consume("rejoin-token"))
                .willReturn(Optional.of(activeUser.getUserId()));
        given(userRepository.findByIdForUpdate(activeUser.getUserId()))
                .willReturn(Optional.of(activeUser));

        assertThatThrownBy(() ->
                rejoinApplicationService.rejoin(new RejoinCommand("rejoin-token")))
                .isInstanceOf(InvalidRejoinTokenException.class);

        then(loginSessionIssuer).shouldHaveNoInteractions();
    }

    @Test
    void rejoin_토큰의_사용자가_없으면_A014로_거부한다() {
        UUID missingUserId = UUID.randomUUID();
        given(rejoinTokenRepository.consume("rejoin-token"))
                .willReturn(Optional.of(missingUserId));
        given(userRepository.findByIdForUpdate(missingUserId)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                rejoinApplicationService.rejoin(new RejoinCommand("rejoin-token")))
                .isInstanceOf(InvalidRejoinTokenException.class);
    }

    private User createWithdrawnUser() {
        User user = User.create("사용자", "user@example.com", null, UserRole.SELLER, true);
        user.withdraw();
        return user;
    }

    private OAuthLoginCompletedResult completedResult(User user) {
        return new OAuthLoginCompletedResult(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getRoles(),
                "access-token",
                "refresh-token",
                "Bearer",
                Instant.parse("2026-07-30T12:15:00Z"),
                false
        );
    }
}
