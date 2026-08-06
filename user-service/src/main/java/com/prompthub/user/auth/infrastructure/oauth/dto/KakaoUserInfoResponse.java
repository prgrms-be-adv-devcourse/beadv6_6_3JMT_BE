package com.prompthub.user.auth.infrastructure.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoUserInfoResponse(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {
    public record KakaoAccount(
            String email,
            KakaoProfile profile
    ) {}

    public record KakaoProfile(
            String nickname,
            @JsonProperty("profile_image_url") String profileImageUrl
    ) {}
}
