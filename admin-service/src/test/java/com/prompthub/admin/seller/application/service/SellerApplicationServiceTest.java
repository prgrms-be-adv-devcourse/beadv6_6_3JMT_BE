package com.prompthub.admin.seller.application.service;

import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SellerApplicationServiceTest {

	@Mock
	private SellerRegisterRepository sellerRegisterRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private AuthorizationCacheRepository authorizationCacheRepository;

	@InjectMocks
	private SellerApplicationService sellerApplicationService;

	@Test
	void 승인하면_유저에게_SELLER_역할을_주고_인가캐시를_무효화한다() throws Exception {
		UUID registerId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		SellerRegister register = newRegister(registerId, userId, SellerRegisterStatus.PENDING);
		User user = newUser(userId);
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.of(register));
		given(userRepository.findById(userId)).willReturn(Optional.of(user));
		given(sellerRegisterRepository.save(register)).willReturn(register);
		given(userRepository.save(user)).willReturn(user);

		SellerRegisterReviewResult result = sellerApplicationService.approve(new ApproveSellerCommand(registerId));

		assertThat(result.status()).isEqualTo(SellerRegisterStatus.APPROVED);
		assertThat(user.getRoles()).contains(UserRole.SELLER);
		then(authorizationCacheRepository).should().evict(userId);
	}

	@Test
	void 반려하면_인가캐시를_건드리지_않는다() throws Exception {
		UUID registerId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		SellerRegister register = newRegister(registerId, userId, SellerRegisterStatus.PENDING);
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.of(register));
		given(sellerRegisterRepository.save(register)).willReturn(register);

		SellerRegisterReviewResult result = sellerApplicationService.reject(
			new RejectSellerCommand(registerId, "포트폴리오 미확인"));

		assertThat(result.status()).isEqualTo(SellerRegisterStatus.REJECTED);
		assertThat(result.rejectReason()).isEqualTo("포트폴리오 미확인");
		then(authorizationCacheRepository).shouldHaveNoInteractions();
	}

	@Test
	void 이미_심사된_신청은_승인할_수_없다() throws Exception {
		UUID registerId = UUID.randomUUID();
		SellerRegister register = newRegister(registerId, UUID.randomUUID(), SellerRegisterStatus.APPROVED);
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.of(register));

		assertThatThrownBy(() -> sellerApplicationService.approve(new ApproveSellerCommand(registerId)))
			.isInstanceOf(AdminException.class);
	}

	@Test
	void 존재하지_않는_신청은_SELLER_REGISTER_NOT_FOUND를_던진다() {
		UUID registerId = UUID.randomUUID();
		given(sellerRegisterRepository.findById(registerId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> sellerApplicationService.approve(new ApproveSellerCommand(registerId)))
			.isInstanceOf(AdminException.class);
	}

	// User/SellerRegister는 domain-model.md 정책상 public 생성자/빌더가 없어
	// 테스트 전용 리플렉션 헬퍼로 픽스처를 만든다(Task 3과 동일한 전례).
	private static SellerRegister newRegister(UUID registerId, UUID userId, SellerRegisterStatus status) throws Exception {
		SellerRegister register = newInstance(SellerRegister.class);
		setField(register, SellerRegister.class, "sellerRegisterId", registerId);
		setField(register, SellerRegister.class, "userId", userId);
		setField(register, SellerRegister.class, "status", status);
		return register;
	}

	private static User newUser(UUID userId) throws Exception {
		User user = newInstance(User.class);
		setField(user, User.class, "userId", userId);
		setField(user, User.class, "name", "테스트유저");
		setField(user, User.class, "email", "test@example.com");
		return user;
	}

	private static <T> T newInstance(Class<T> type) throws Exception {
		Constructor<T> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static void setField(Object target, Class<?> type, String fieldName, Object value) throws Exception {
		Field field = type.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
