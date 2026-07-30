package com.prompthub.user.auth.application.dto;

public sealed interface OAuthLoginResult
        permits OAuthLoginCompletedResult, OAuthRejoinRequiredResult {

    OAuthLoginStatus loginStatus();

    boolean isNewUser();
}
