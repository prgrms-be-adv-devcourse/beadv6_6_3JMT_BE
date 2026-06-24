package com.prompthub.user.admin.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.admin.application.dto.AdminUserListQuery;
import com.prompthub.user.admin.application.dto.AdminUserPageResult;
import com.prompthub.user.admin.application.dto.AdminUserStatusResult;
import com.prompthub.user.admin.application.dto.AdminUserSummaryResult;
import com.prompthub.user.admin.application.dto.ChangeUserStatusCommand;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import com.prompthub.user.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AdminUserApplicationServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminUserApplicationService adminUserApplicationService;

    private User mockUser(UUID id, String name, String email, UserRole role, UserStatus status) {
        User user = mock(User.class);
        given(user.getUserId()).willReturn(id);
        given(user.getName()).willReturn(name);
        given(user.getEmail()).willReturn(email);
        given(user.getPrimaryRole()).willReturn(role);
        given(user.getStatus()).willReturn(status);
        return user;
    }

    @Test
    void listUsers_필터없음_전체조회_repository_호출() {
        AdminUserListQuery query = new AdminUserListQuery(null, null, null, 1, 20);
        given(userRepository.findUsers(null, null, null, 0, 20)).willReturn(List.of());
        given(userRepository.countUsers(null, null, null)).willReturn(0L);

        adminUserApplicationService.listUsers(query);

        then(userRepository).should().findUsers(null, null, null, 0, 20);
        then(userRepository).should().countUsers(null, null, null);
    }

    @Test
    void listUsers_status_필터_전달됨() {
        AdminUserListQuery query = new AdminUserListQuery(UserStatus.ACTIVE, null, null, 1, 20);
        given(userRepository.findUsers(UserStatus.ACTIVE, null, null, 0, 20)).willReturn(List.of());
        given(userRepository.countUsers(UserStatus.ACTIVE, null, null)).willReturn(0L);

        adminUserApplicationService.listUsers(query);

        then(userRepository).should().findUsers(UserStatus.ACTIVE, null, null, 0, 20);
    }

    @Test
    void listUsers_role_필터_전달됨() {
        AdminUserListQuery query = new AdminUserListQuery(null, UserRole.BUYER, null, 1, 20);
        given(userRepository.findUsers(null, UserRole.BUYER, null, 0, 20)).willReturn(List.of());
        given(userRepository.countUsers(null, UserRole.BUYER, null)).willReturn(0L);

        adminUserApplicationService.listUsers(query);

        then(userRepository).should().findUsers(null, UserRole.BUYER, null, 0, 20);
    }

    @Test
    void listUsers_keyword_필터_전달됨() {
        AdminUserListQuery query = new AdminUserListQuery(null, null, "홍길동", 1, 20);
        given(userRepository.findUsers(null, null, "홍길동", 0, 20)).willReturn(List.of());
        given(userRepository.countUsers(null, null, "홍길동")).willReturn(0L);

        adminUserApplicationService.listUsers(query);

        then(userRepository).should().findUsers(null, null, "홍길동", 0, 20);
    }

    @Test
    void listUsers_page_1인덱스를_0인덱스로_변환() {
        AdminUserListQuery query = new AdminUserListQuery(null, null, null, 2, 10);
        given(userRepository.findUsers(null, null, null, 1, 10)).willReturn(List.of());
        given(userRepository.countUsers(null, null, null)).willReturn(0L);

        adminUserApplicationService.listUsers(query);

        then(userRepository).should().findUsers(null, null, null, 1, 10);
    }

    @Test
    void listUsers_결과_User를_AdminUserSummaryResult로_매핑() {
        UUID userId = UUID.randomUUID();
        User user = mockUser(userId, "홍길동", "hong@example.com", UserRole.BUYER, UserStatus.ACTIVE);
        AdminUserListQuery query = new AdminUserListQuery(null, null, null, 1, 20);
        given(userRepository.findUsers(null, null, null, 0, 20)).willReturn(List.of(user));
        given(userRepository.countUsers(null, null, null)).willReturn(1L);

        AdminUserPageResult result = adminUserApplicationService.listUsers(query);

        assertThat(result.users()).hasSize(1);
        AdminUserSummaryResult item = result.users().get(0);
        assertThat(item.userId()).isEqualTo(userId);
        assertThat(item.name()).isEqualTo("홍길동");
        assertThat(item.email()).isEqualTo("hong@example.com");
        assertThat(item.role()).isEqualTo(UserRole.BUYER);
        assertThat(item.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void listUsers_페이지_메타_정확히_반환() {
        AdminUserListQuery query = new AdminUserListQuery(null, null, null, 2, 10);
        given(userRepository.findUsers(null, null, null, 1, 10)).willReturn(List.of());
        given(userRepository.countUsers(null, null, null)).willReturn(25L);

        AdminUserPageResult result = adminUserApplicationService.listUsers(query);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.total()).isEqualTo(25L);
    }

    @Test
    void listUsers_다음_페이지_있으면_hasNext_true() {
        // page=1, size=20, total=21 → hasNext=true
        AdminUserListQuery query = new AdminUserListQuery(null, null, null, 1, 20);
        given(userRepository.findUsers(null, null, null, 0, 20)).willReturn(List.of());
        given(userRepository.countUsers(null, null, null)).willReturn(21L);

        AdminUserPageResult result = adminUserApplicationService.listUsers(query);

        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void listUsers_마지막_페이지면_hasNext_false() {
        // page=1, size=20, total=15 → hasNext=false
        AdminUserListQuery query = new AdminUserListQuery(null, null, null, 1, 20);
        given(userRepository.findUsers(null, null, null, 0, 20)).willReturn(List.of());
        given(userRepository.countUsers(null, null, null)).willReturn(15L);

        AdminUserPageResult result = adminUserApplicationService.listUsers(query);

        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void listUsers_정확히_마지막_아이템이면_hasNext_false() {
        // page=1, size=20, total=20 → hasNext=false
        AdminUserListQuery query = new AdminUserListQuery(null, null, null, 1, 20);
        given(userRepository.findUsers(null, null, null, 0, 20)).willReturn(List.of());
        given(userRepository.countUsers(null, null, null)).willReturn(20L);

        AdminUserPageResult result = adminUserApplicationService.listUsers(query);

        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void changeUserStatus_사용자_없으면_예외() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                adminUserApplicationService.changeUserStatus(
                        new ChangeUserStatusCommand(userId, UserStatus.BLOCKED)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void changeUserStatus_BLOCKED_요청시_block_호출() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        given(user.getUserId()).willReturn(userId);
        given(user.getStatus()).willReturn(UserStatus.BLOCKED);
        given(user.getUpdatedAt()).willReturn(LocalDateTime.now());
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.save(user)).willReturn(user);

        AdminUserStatusResult result = adminUserApplicationService.changeUserStatus(
                new ChangeUserStatusCommand(userId, UserStatus.BLOCKED));

        then(user).should().block();
        assertThat(result.status()).isEqualTo(UserStatus.BLOCKED);
    }

    @Test
    void changeUserStatus_ACTIVE_요청시_activate_호출() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        given(user.getUserId()).willReturn(userId);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(user.getUpdatedAt()).willReturn(LocalDateTime.now());
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.save(user)).willReturn(user);

        AdminUserStatusResult result = adminUserApplicationService.changeUserStatus(
                new ChangeUserStatusCommand(userId, UserStatus.ACTIVE));

        then(user).should().activate();
        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void changeUserStatus_WITHDRAWN_요청시_withdraw_호출() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        given(user.getUserId()).willReturn(userId);
        given(user.getStatus()).willReturn(UserStatus.WITHDRAWN);
        given(user.getUpdatedAt()).willReturn(LocalDateTime.now());
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.save(user)).willReturn(user);

        adminUserApplicationService.changeUserStatus(
                new ChangeUserStatusCommand(userId, UserStatus.WITHDRAWN));

        then(user).should().withdraw();
    }
}
