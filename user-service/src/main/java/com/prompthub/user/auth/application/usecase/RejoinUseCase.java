package com.prompthub.user.auth.application.usecase;

import com.prompthub.user.auth.application.dto.OAuthLoginCompletedResult;
import com.prompthub.user.auth.application.dto.RejoinCommand;

public interface RejoinUseCase {

    OAuthLoginCompletedResult rejoin(RejoinCommand command);
}
