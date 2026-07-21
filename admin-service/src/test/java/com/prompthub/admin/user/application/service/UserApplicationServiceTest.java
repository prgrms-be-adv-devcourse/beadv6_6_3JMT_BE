package com.prompthub.admin.user.application.service;

import com.prompthub.admin.auth.application.usecase.SessionRevocationUseCase;
import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.application.dto.ChangeUserStatusCommand;
import com.prompthub.admin.user.application.dto.UserListQuery;
import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserApplicationServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private AuthorizationCacheRepository authorizationCacheRepository;

	@Mock
	private SessionRevocationUseCase sessionRevocationUseCase;

	@InjectMocks
	private UserApplicationService userApplicationService;

	@Test
	void 목록_조회는_0base_페이지로_변환해서_리포지토리에_전달한다() {
		given(userRepository.findUsers(null, null, null, 0, 20)).willReturn(List.of());
		given(userRepository.countUsers(null, null, null)).willReturn(0L);

		UserPageResult result = userApplicationService.listUsers(new UserListQuery(null, null, null, 1, 20));

		assertThat(result.page()).isEqualTo(1);
		assertThat(result.hasNext()).isFalse();
	}

	@Test
	void WITHDRAWN으로_바꾸면_세션을_전부_폐기한다() throws Exception {
		UUID userId = UUID.randomUUID();
		User user = newUser(userId, UserStatus.ACTIVE);
		given(userRepository.findById(userId)).willReturn(Optional.of(user));
		given(userRepository.save(user)).willReturn(user);

		UserStatusResult result = userApplicationService.changeUserStatus(
			new ChangeUserStatusCommand(userId, UserStatus.WITHDRAWN));

		assertThat(result.status()).isEqualTo(UserStatus.WITHDRAWN);
		then(sessionRevocationUseCase).should().revoke(userId);
		then(authorizationCacheRepository).should(never()).evict(any());
	}

	@Test
	void BLOCKED로_바꾸면_인가캐시만_무효화한다() throws Exception {
		UUID userId = UUID.randomUUID();
		User user = newUser(userId, UserStatus.ACTIVE);
		given(userRepository.findById(userId)).willReturn(Optional.of(user));
		given(userRepository.save(user)).willReturn(user);

		userApplicationService.changeUserStatus(new ChangeUserStatusCommand(userId, UserStatus.BLOCKED));

		then(authorizationCacheRepository).should().evict(userId);
		then(sessionRevocationUseCase).should(never()).revoke(any());
	}

	@Test
	void 대상_사용자가_없으면_USER_NOT_FOUND를_던진다() {
		UUID userId = UUID.randomUUID();
		given(userRepository.findById(userId)).willReturn(Optional.empty());

		assertThatThrownBy(() ->
			userApplicationService.changeUserStatus(new ChangeUserStatusCommand(userId, UserStatus.BLOCKED)))
			.isInstanceOf(AdminException.class);
	}

	// 테스트 전용 헬퍼 — User는 domain-model.md 정책상 public 생성자/빌더가 없으므로
	// 리플렉션으로 픽스처를 만든다(admin-service 기존 컨벤션에 정적 팩토리가 없는
	// 재매핑 엔티티가 없어 참고할 전례가 없다 — 최소한의 리플렉션으로 대체).
	private static User newUser(UUID userId, UserStatus status) throws Exception {
		Constructor<User> constructor = User.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		User user = constructor.newInstance();
		setField(user, "userId", userId);
		setField(user, "name", "테스트유저");
		setField(user, "email", "test@example.com");
		setField(user, "status", status);
		return user;
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = User.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
