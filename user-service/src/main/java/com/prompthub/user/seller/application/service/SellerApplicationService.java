package com.prompthub.user.seller.application.service;

import com.prompthub.user.seller.application.dto.RegisterSellerCommand;
import com.prompthub.user.seller.application.dto.RegisterSellerResult;
import com.prompthub.user.seller.application.usecase.SellerUseCase;
import com.prompthub.user.seller.domain.exception.SellerAlreadyAppliedException;
import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.seller.domain.repository.SellerRegisterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerApplicationService implements SellerUseCase {

    private final SellerRegisterRepository sellerRegisterRepository;

    @Override
    @Transactional
    public RegisterSellerResult register(RegisterSellerCommand command) {
        if (sellerRegisterRepository.existsByUserIdAndStatusIn(
                command.userId(),
                List.of(SellerRegisterStatus.PENDING, SellerRegisterStatus.APPROVED))) {
            throw new SellerAlreadyAppliedException();
        }

        SellerRegister register = SellerRegister.create(
                command.userId(),
                command.categories(),
                command.introduction(),
                command.portfolioUrl(),
                command.agreedToTerms()
        );

        return RegisterSellerResult.from(sellerRegisterRepository.save(register));
    }
}
