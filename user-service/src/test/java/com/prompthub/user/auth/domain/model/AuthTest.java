package com.prompthub.user.auth.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTest {

    @Test
    void create_모든_필드_정상_설정() {
        UUID userId = UUID.randomUUID();
        OAuthProvider provider = OAuthProvider.KAKAO;
        String providerUserId = "kakao_123456";

        Auth auth = Auth.create(userId, provider, providerUserId);

        assertThat(auth.getUserId()).isEqualTo(userId);
        assertThat(auth.getProvider()).isEqualTo(provider);
        assertThat(auth.getProviderUserId()).isEqualTo(providerUserId);
    }

    @Test
    void create_authId_자동_생성() {
        Auth auth = Auth.create(UUID.randomUUID(), OAuthProvider.KAKAO, "provider_id");

        assertThat(auth.getAuthId()).isNotNull();
    }

    @Test
    void create_connectedAt_자동_설정() {
        Instant before = Instant.now().minusSeconds(1);

        Auth auth = Auth.create(UUID.randomUUID(), OAuthProvider.KAKAO, "provider_id");

        assertThat(auth.getConnectedAt()).isAfter(before).isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void create_호출마다_서로_다른_authId() {
        UUID userId = UUID.randomUUID();

        Auth auth1 = Auth.create(userId, OAuthProvider.KAKAO, "id_1");
        Auth auth2 = Auth.create(userId, OAuthProvider.KAKAO, "id_2");

        assertThat(auth1.getAuthId()).isNotEqualTo(auth2.getAuthId());
    }

    @Test
    void create_각_provider_정상_생성() {
        UUID userId = UUID.randomUUID();

        Auth kakaoAuth = Auth.create(userId, OAuthProvider.KAKAO, "k_id");
        Auth naverAuth = Auth.create(userId, OAuthProvider.NAVER, "n_id");
        Auth googleAuth = Auth.create(userId, OAuthProvider.GOOGLE, "g_id");

        assertThat(kakaoAuth.getProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(naverAuth.getProvider()).isEqualTo(OAuthProvider.NAVER);
        assertThat(googleAuth.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
    }
}
