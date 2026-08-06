package com.prompthub.user.auth.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prompthub.user.auth.application.dto.OAuthLoginCompletedResult;
import com.prompthub.user.auth.application.dto.RejoinCommand;
import com.prompthub.user.auth.application.usecase.RejoinUseCase;
import com.prompthub.user.auth.domain.exception.InvalidRejoinTokenException;
import com.prompthub.user.auth.domain.repository.RejoinTokenRepository;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserStatus;
import com.prompthub.user.user.domain.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RejoinApplicationService implements RejoinUseCase {

    private final RejoinTokenRepository rejoinTokenRepository;
    private final UserRepository userRepository;
    private final LoginSessionIssuer loginSessionIssuer;

    @Override
    @Transactional
    public OAuthLoginCompletedResult rejoin(RejoinCommand command) {
        UUID userId = rejoinTokenRepository.consume(command.rejoinToken())
                .orElseThrow(InvalidRejoinTokenException::new);
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(InvalidRejoinTokenException::new);

        if (user.getStatus() != UserStatus.WITHDRAWN) {
            throw new InvalidRejoinTokenException();
        }

        user.activate();
        return loginSessionIssuer.issue(user, false);
    }
}
