package com.prompthub.user.auth.domain.model;

import com.prompthub.user.auth.domain.exception.UnsupportedOAuthProviderException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthProviderTest {

    @Test
    void fromString_소문자_입력_성공() {
        assertThat(OAuthProvider.fromString("kakao")).isEqualTo(OAuthProvider.KAKAO);
        assertThat(OAuthProvider.fromString("naver")).isEqualTo(OAuthProvider.NAVER);
        assertThat(OAuthProvider.fromString("google")).isEqualTo(OAuthProvider.GOOGLE);
    }

    @Test
    void fromString_대문자_입력_성공() {
        assertThat(OAuthProvider.fromString("KAKAO")).isEqualTo(OAuthProvider.KAKAO);
    }

    @Test
    void fromString_혼합_대소문자_성공() {
        assertThat(OAuthProvider.fromString("Kakao")).isEqualTo(OAuthProvider.KAKAO);
        assertThat(OAuthProvider.fromString("Naver")).isEqualTo(OAuthProvider.NAVER);
    }

    @Test
    void fromString_미지원_공급자_예외() {
        assertThatThrownBy(() -> OAuthProvider.fromString("twitter"))
                .isInstanceOf(UnsupportedOAuthProviderException.class)
                .hasMessageContaining("twitter");
    }

    @Test
    void fromString_빈_문자열_예외() {
        assertThatThrownBy(() -> OAuthProvider.fromString(""))
                .isInstanceOf(UnsupportedOAuthProviderException.class);
    }

    @Test
    void fromString_null_예외() {
        assertThatThrownBy(() -> OAuthProvider.fromString(null))
                .isInstanceOf(NullPointerException.class);
    }
}
