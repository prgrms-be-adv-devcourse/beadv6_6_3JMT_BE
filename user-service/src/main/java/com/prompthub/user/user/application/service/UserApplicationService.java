package com.prompthub.user.user.application.service;

import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.user.user.application.dto.UpdateProfileCommand;
import com.prompthub.user.user.application.dto.UpdateProfileResult;
import com.prompthub.user.user.application.dto.UserResult;
import com.prompthub.user.user.application.usecase.UserUseCase;
import com.prompthub.user.user.domain.exception.EmailAlreadyUsedException;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserApplicationService implements UserUseCase {

    private final UserRepository userRepository;
    private final SellerRegisterRepository sellerRegisterRepository;

    @Override
    public UserResult getMyProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        SellerRegisterStatus sellerStatus = sellerRegisterRepository.findLatestByUserId(userId)
                .map(sr -> sr.getStatus())
                .orElse(null);

        return UserResult.from(user, sellerStatus);
    }

    @Override
    @Transactional
    public UpdateProfileResult updateProfile(UpdateProfileCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(UserNotFoundException::new);

        String updatedName = null;
        String updatedEmail = null;

        if (command.name() != null) {
            user.updateName(command.name());
            updatedName = command.name();
        }

        if (command.email() != null && !command.email().equals(user.getEmail())) {
            if (userRepository.existsByEmail(command.email())) {
                throw new EmailAlreadyUsedException();
            }
            user.updateEmail(command.email());
            updatedEmail = command.email();
        }

        return new UpdateProfileResult(user.getUserId(), updatedName, updatedEmail);
    }
}
