package com.prompthub.user.auth.infrastructure.oauth;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.prompthub.user.auth.application.dto.OAuthUserInfo;
import com.prompthub.user.auth.domain.exception.OAuthVerificationFailedException;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoUserInfoClientAdapterTest {

    private HttpServer server;
    private KakaoUserInfoClientAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        adapter = new KakaoUserInfoClientAdapter(baseUrl, JsonMapper.builder().findAndAddModules().build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fetchUserInfo_정상_응답_파싱() {
        stubResponse(200, """
                {
                    "id": 123456789,
                    "kakao_account": {
                        "email": "test@kakao.com",
                        "profile": {
                            "nickname": "테스트유저",
                            "profile_image_url": "https://img.kakao.com/profile.jpg"
                        }
                    }
                }
                """);

        OAuthUserInfo result = adapter.fetchUserInfo("valid-token");

        assertThat(result.oauthId()).isEqualTo("123456789");
        assertThat(result.email()).isEqualTo("test@kakao.com");
        assertThat(result.nickname()).isEqualTo("테스트유저");
        assertThat(result.profileImageUrl()).isEqualTo("https://img.kakao.com/profile.jpg");
    }

    @Test
    void fetchUserInfo_유효하지_않은_토큰이면_OAuthVerificationFailedException() {
        stubResponse(401, """
                {
                    "msg": "this access token does not exist",
                    "code": -401
                }
                """);

        assertThatThrownBy(() -> adapter.fetchUserInfo("invalid-token"))
                .isInstanceOf(OAuthVerificationFailedException.class)
                .hasMessageContaining("this access token does not exist");
    }

    @Test
    void fetchUserInfo_이메일_동의_안했으면_OAuthVerificationFailedException() {
        stubResponse(200, """
                {
                    "id": 123456789,
                    "kakao_account": {
                        "profile": {
                            "nickname": "테스트유저"
                        }
                    }
                }
                """);

        assertThatThrownBy(() -> adapter.fetchUserInfo("valid-token"))
                .isInstanceOf(OAuthVerificationFailedException.class);
    }

    @Test
    void fetchUserInfo_닉네임_동의_안했으면_OAuthVerificationFailedException() {
        stubResponse(200, """
                {
                    "id": 123456789,
                    "kakao_account": {
                        "email": "test@kakao.com"
                    }
                }
                """);

        assertThatThrownBy(() -> adapter.fetchUserInfo("valid-token"))
                .isInstanceOf(OAuthVerificationFailedException.class);
    }

    private void stubResponse(int status, String body) {
        server.createContext("/v2/user/me", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
