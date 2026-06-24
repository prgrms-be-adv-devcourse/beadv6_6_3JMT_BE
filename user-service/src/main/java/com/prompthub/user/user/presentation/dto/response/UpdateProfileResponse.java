package com.prompthub.user.user.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.prompthub.user.user.application.dto.UpdateProfileResult;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateProfileResponse(
        UUID id,
        String name,
        String email
) {
    public static UpdateProfileResponse from(UpdateProfileResult result) {
        return new UpdateProfileResponse(result.userId(), result.name(), result.email());
    }
}
