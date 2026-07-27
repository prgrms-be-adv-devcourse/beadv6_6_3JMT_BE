package com.prompthub.admin.user.service;

import com.prompthub.admin.auth.service.AuthService;
import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.user.dto.ApproveSellerCommand;
import com.prompthub.admin.user.dto.RejectSellerCommand;
import com.prompthub.admin.user.dto.SellerRegisterListQuery;
import com.prompthub.admin.user.dto.SellerRegisterPageResult;
import com.prompthub.admin.user.dto.SellerRegisterReviewResult;
import com.prompthub.admin.user.dto.SellerRegisterSummaryResult;
import com.prompthub.admin.user.entity.SellerRegister;
import com.prompthub.admin.user.entity.enums.SellerRegisterStatus;
import com.prompthub.admin.user.repository.SellerRegisterRepository;
import com.prompthub.admin.user.entity.User;
import com.prompthub.admin.user.entity.enums.UserRole;
import com.prompthub.admin.user.repository.UserRepository;
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
public class SellerService {

	private final SellerRegisterRepository sellerRegisterRepository;
	private final UserRepository userRepository;
	private final AuthService authService;

	public SellerRegisterPageResult listSellerRegisters(SellerRegisterListQuery query) {
		List<SellerRegister> registers = sellerRegisterRepository.findAll(
			query.status(), query.page(), query.size());
		long total = sellerRegisterRepository.count(query.status());

		List<UUID> userIds = registers.stream()
			.map(SellerRegister::getUserId)
			.toList();

		Map<UUID, User> userMap = userRepository.findAllByIds(userIds).stream()
			.collect(Collectors.toMap(User::getUserId, Function.identity()));

		List<SellerRegisterSummaryResult> items = registers.stream()
			.filter(r -> userMap.containsKey(r.getUserId()))
			.map(r -> SellerRegisterSummaryResult.of(r, userMap.get(r.getUserId())))
			.toList();

		boolean hasNext = total > (long) (query.page() + 1) * query.size();

		return new SellerRegisterPageResult(items, query.page(), query.size(), total, hasNext);
	}

	@Transactional
	public SellerRegisterReviewResult approve(ApproveSellerCommand command) {
		SellerRegister register = findRegister(command.registerId());
		guardPending(register);

		register.approve();
		sellerRegisterRepository.save(register);

		User user = userRepository.findById(register.getUserId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.USER_NOT_FOUND));
		user.addRole(UserRole.SELLER);
		userRepository.save(user);
		authService.evictAuthorizationCache(user.getUserId());

		return SellerRegisterReviewResult.from(register);
	}

	@Transactional
	public SellerRegisterReviewResult reject(RejectSellerCommand command) {
		SellerRegister register = findRegister(command.registerId());
		guardPending(register);

		register.reject(command.rejectReason());
		sellerRegisterRepository.save(register);

		return SellerRegisterReviewResult.from(register);
	}

	private SellerRegister findRegister(UUID registerId) {
		return sellerRegisterRepository.findById(registerId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.SELLER_REGISTER_NOT_FOUND));
	}

	private static void guardPending(SellerRegister register) {
		if (register.getStatus() != SellerRegisterStatus.PENDING) {
			throw new AdminException(AdminErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
