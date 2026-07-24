package com.prompthub.admin.seller.application.service;

import com.prompthub.admin.auth.application.service.SessionRevocationApplicationService;
import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterListQuery;
import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterSummaryResult;
import com.prompthub.admin.seller.application.usecase.SellerUseCase;
import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.repository.UserRepository;
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
public class SellerApplicationService implements SellerUseCase {

	private final SellerRegisterRepository sellerRegisterRepository;
	private final UserRepository userRepository;
	private final SessionRevocationApplicationService sessionRevocationApplicationService;

	@Override
	public SellerRegisterPageResult listSellerRegisters(SellerRegisterListQuery query) {
		int zeroBasedPage = query.page() - 1;

		List<SellerRegister> registers = sellerRegisterRepository.findAll(
			query.status(), zeroBasedPage, query.size());
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

		boolean hasNext = total > (long) query.page() * query.size();

		return new SellerRegisterPageResult(items, query.page(), query.size(), total, hasNext);
	}

	@Override
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
		sessionRevocationApplicationService.evictAuthorizationCache(user.getUserId());

		return SellerRegisterReviewResult.from(register);
	}

	@Override
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
