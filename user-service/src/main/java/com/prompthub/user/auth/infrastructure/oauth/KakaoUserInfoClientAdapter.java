package com.prompthub.user.auth.infrastructure.oauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.prompthub.user.auth.application.client.KakaoUserInfoClient;
import com.prompthub.user.auth.application.dto.OAuthUserInfo;
import com.prompthub.user.auth.domain.exception.OAuthVerificationFailedException;
import com.prompthub.user.auth.infrastructure.oauth.dto.KakaoErrorResponse;
import com.prompthub.user.auth.infrastructure.oauth.dto.KakaoUserInfoResponse;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class KakaoUserInfoClientAdapter implements KakaoUserInfoClient {

    private static final String USER_ME_PATH = "/v2/user/me";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public KakaoUserInfoClientAdapter(
            @Value("${oauth.kakao.base-url:https://kapi.kakao.com}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public OAuthUserInfo fetchUserInfo(String accessToken) {
        KakaoUserInfoResponse response = restClient.get()
                .uri(USER_ME_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    String reason = parseErrorMessage(resp);
                    log.warn("카카오 사용자 정보 조회 실패. status={}, reason={}", resp.getStatusCode(), reason);
                    throw new OAuthVerificationFailedException(reason);
                })
                .body(KakaoUserInfoResponse.class);

        return toOAuthUserInfo(response);
    }

    private OAuthUserInfo toOAuthUserInfo(KakaoUserInfoResponse response) {
        if (response == null || response.id() == null) {
            throw new OAuthVerificationFailedException("카카오 응답에 사용자 식별자가 없습니다.");
        }

        KakaoUserInfoResponse.KakaoAccount account = response.kakaoAccount();
        String email = account != null ? account.email() : null;
        if (email == null || email.isBlank()) {
            throw new OAuthVerificationFailedException("카카오 계정에서 이메일 정보를 가져올 수 없습니다. 이메일 제공에 동의해야 합니다.");
        }

        KakaoUserInfoResponse.KakaoProfile profile = account.profile();
        String nickname = profile != null ? profile.nickname() : null;
        if (nickname == null || nickname.isBlank()) {
            throw new OAuthVerificationFailedException("카카오 계정에서 닉네임 정보를 가져올 수 없습니다. 프로필 정보 제공에 동의해야 합니다.");
        }
        String profileImageUrl = profile.profileImageUrl();

        return new OAuthUserInfo(String.valueOf(response.id()), email, nickname, profileImageUrl);
    }

    private String parseErrorMessage(ClientHttpResponse resp) {
        try {
            String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
            KakaoErrorResponse error = objectMapper.readValue(body, KakaoErrorResponse.class);
            return error.msg();
        } catch (IOException e) {
            log.warn("카카오 에러 응답 파싱 실패 — cause={}", e.getMessage(), e);
            return "카카오 API 오류";
        }
    }
}
