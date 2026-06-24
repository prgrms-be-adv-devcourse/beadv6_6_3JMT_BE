package com.prompthub.user.admin.application.service;

import com.prompthub.exception.BusinessException;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterListQuery;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterPageResult;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterReviewResult;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterSummaryResult;
import com.prompthub.user.admin.application.dto.ApproveSellerCommand;
import com.prompthub.user.admin.application.dto.RejectSellerCommand;
import com.prompthub.user.admin.application.usecase.AdminSellerUseCase;
import com.prompthub.user.global.exception.UserErrorCode;
import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.repository.UserRepository;
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
public class AdminSellerApplicationService implements AdminSellerUseCase {

    private final SellerRegisterRepository sellerRegisterRepository;
    private final UserRepository userRepository;

    @Override
    public AdminSellerRegisterPageResult listSellerRegisters(AdminSellerRegisterListQuery query) {
        int zeroBasedPage = query.page() - 1;

        List<SellerRegister> registers = sellerRegisterRepository.findAll(
                query.status(), zeroBasedPage, query.size());
        long total = sellerRegisterRepository.count(query.status());

        List<UUID> userIds = registers.stream()
                .map(SellerRegister::getUserId)
                .toList();

        Map<UUID, User> userMap = userRepository.findAllByIds(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));

        List<AdminSellerRegisterSummaryResult> items = registers.stream()
                .filter(r -> userMap.containsKey(r.getUserId()))
                .map(r -> AdminSellerRegisterSummaryResult.of(r, userMap.get(r.getUserId())))
                .toList();

        boolean hasNext = total > (long) query.page() * query.size();

        return new AdminSellerRegisterPageResult(items, query.page(), query.size(), total, hasNext);
    }

    @Override
    @Transactional
    public AdminSellerRegisterReviewResult approve(ApproveSellerCommand command) {
        SellerRegister register = findRegister(command.registerId());
        guardPending(register);

        register.approve();
        sellerRegisterRepository.save(register);

        User user = userRepository.findById(register.getUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.AUTH_NOT_FOUND));
        user.addRole(UserRole.SELLER);
        userRepository.save(user);

        return AdminSellerRegisterReviewResult.from(register);
    }

    @Override
    @Transactional
    public AdminSellerRegisterReviewResult reject(RejectSellerCommand command) {
        SellerRegister register = findRegister(command.registerId());
        guardPending(register);

        register.reject(command.rejectReason());
        sellerRegisterRepository.save(register);

        return AdminSellerRegisterReviewResult.from(register);
    }

    private SellerRegister findRegister(UUID registerId) {
        return sellerRegisterRepository.findById(registerId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.AUTH_SELLER_APPLICATION_NOT_FOUND));
    }

    private static void guardPending(SellerRegister register) {
        if (register.getStatus() != SellerRegisterStatus.PENDING) {
            throw new BusinessException(UserErrorCode.VALIDATION_FAILED);
        }
    }
}
