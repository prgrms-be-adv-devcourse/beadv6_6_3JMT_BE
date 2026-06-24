package com.prompthub.user.user.application.service;

import com.prompthub.user.user.application.dto.UserResult;
import com.prompthub.user.user.application.usecase.UserUseCase;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
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

    @Override
    public UserResult getMyProfile(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResult::from)
                .orElseThrow(UserNotFoundException::new);
    }
}
