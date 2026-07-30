package com.prompthub.user.auth.application.dto;

import java.time.Instant;

public record OAuthRejoinRequiredResult(
        String rejoinToken,
        Instant rejoinExpiresAt
) implements OAuthLoginResult {

    @Override
    public OAuthLoginStatus loginStatus() {
        return OAuthLoginStatus.REJOIN_REQUIRED;
    }

    @Override
    public boolean isNewUser() {
        return false;
    }
}
