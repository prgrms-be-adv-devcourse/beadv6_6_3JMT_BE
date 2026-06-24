package com.prompthub.user.user.application.usecase;

import com.prompthub.user.user.application.dto.UpdateProfileCommand;
import com.prompthub.user.user.application.dto.UpdateProfileResult;
import com.prompthub.user.user.application.dto.UserResult;

import java.util.UUID;

public interface UserUseCase {
    UserResult getMyProfile(UUID userId);
    UpdateProfileResult updateProfile(UpdateProfileCommand command);
}
