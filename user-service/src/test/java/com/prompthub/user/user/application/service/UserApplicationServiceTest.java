package com.prompthub.user.user.application.service;

import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.user.user.application.dto.UpdateProfileCommand;
import com.prompthub.user.user.application.dto.UpdateProfileResult;
import com.prompthub.user.user.application.dto.UserResult;
import com.prompthub.user.user.domain.exception.EmailAlreadyUsedException;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserApplicationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SellerRegisterRepository sellerRegisterRepository;

    @InjectMocks
    private UserApplicationService userApplicationService;

    private static final UUID USER_ID = UUID.randomUUID();

    private User createUser() {
        return User.create("홍길동", "hong@example.com", null, UserRole.BUYER, true);
    }

    @Test
    void updateProfile_name만_수정_시_name만_결과에_포함() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        UpdateProfileResult result = userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, "새이름", null));

        assertThat(result.name()).isEqualTo("새이름");
        assertThat(result.email()).isNull();
        assertThat(user.getName()).isEqualTo("새이름");
    }

    @Test
    void updateProfile_email만_수정_시_email만_결과에_포함() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByEmail("new@example.com")).willReturn(false);

        UpdateProfileResult result = userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, null, "new@example.com"));

        assertThat(result.name()).isNull();
        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void updateProfile_name과_email_모두_수정() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByEmail("new@example.com")).willReturn(false);

        UpdateProfileResult result = userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, "새이름", "new@example.com"));

        assertThat(result.name()).isEqualTo("새이름");
        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(user.getName()).isEqualTo("새이름");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void updateProfile_모두_null_이면_아무것도_변경_안함() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        UpdateProfileResult result = userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, null, null));

        assertThat(result.name()).isNull();
        assertThat(result.email()).isNull();
        assertThat(user.getName()).isEqualTo("홍길동");
        assertThat(user.getEmail()).isEqualTo("hong@example.com");
    }

    @Test
    void updateProfile_결과에_userId_항상_포함() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        UpdateProfileResult result = userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, "새이름", null));

        assertThat(result.userId()).isEqualTo(user.getUserId());
    }

    @Test
    void updateProfile_존재하지_않는_유저_UserNotFoundException() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, "새이름", null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateProfile_이미_사용중인_email_EmailAlreadyUsedException() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByEmail("taken@example.com")).willReturn(true);

        assertThatThrownBy(() -> userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, null, "taken@example.com")))
                .isInstanceOf(EmailAlreadyUsedException.class);

        assertThat(user.getEmail()).isEqualTo("hong@example.com");
    }

    @Test
    void updateProfile_동일한_email_요청_시_중복_검사_건너뜀() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        UpdateProfileResult result = userApplicationService.updateProfile(
                new UpdateProfileCommand(USER_ID, null, "hong@example.com"));

        assertThat(result.email()).isNull();
        then(userRepository).should(never()).existsByEmail(any());
    }

    @Test
    void getMyProfile_판매자_신청_없는_BUYER_sellerStatus_null() {
        User user = createUser();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(sellerRegisterRepository.findLatestByUserId(USER_ID)).willReturn(Optional.empty());

        UserResult result = userApplicationService.getMyProfile(USER_ID);

        assertThat(result.sellerStatus()).isNull();
        assertThat(result.role()).isEqualTo(UserRole.BUYER);
    }

    @Test
    void getMyProfile_PENDING_신청_있으면_sellerStatus_PENDING() {
        User user = createUser();
        SellerRegister pending = mock(SellerRegister.class);
        given(pending.getStatus()).willReturn(SellerRegisterStatus.PENDING);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(sellerRegisterRepository.findLatestByUserId(USER_ID)).willReturn(Optional.of(pending));

        UserResult result = userApplicationService.getMyProfile(USER_ID);

        assertThat(result.sellerStatus()).isEqualTo(SellerRegisterStatus.PENDING);
    }

    @Test
    void getMyProfile_존재하지_않는_유저_UserNotFoundException() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userApplicationService.getMyProfile(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }
}
