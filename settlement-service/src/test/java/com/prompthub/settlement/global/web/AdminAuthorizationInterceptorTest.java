package com.prompthub.settlement.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminAuthorizationInterceptorTest {

    private final AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor();
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    private HttpServletRequest requestWith(String userId, String role) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getHeader(AuthHeaders.USER_ID)).willReturn(userId);
        given(request.getHeader(AuthHeaders.USER_ROLE)).willReturn(role);
        return request;
    }

    @DisplayName("X-User-Role 에 ADMIN 이 포함되면(콤마 구분 멀티롤 포함) 통과한다")
    @ParameterizedTest(name = "role=\"{0}\"")
    @ValueSource(strings = {"ADMIN", "ADMIN,BUYER", "BUYER,ADMIN", "BUYER,SELLER,ADMIN", "BUYER, ADMIN"})
    void preHandle_roleContainsAdmin_passes(String role) {
        HttpServletRequest request = requestWith("user-1", role);

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
    }

    @DisplayName("X-User-Role 에 ADMIN 이 없으면 FORBIDDEN")
    @ParameterizedTest(name = "role=\"{0}\"")
    @ValueSource(strings = {"BUYER", "SELLER", "BUYER,SELLER", "ADMINISTRATOR", "SUPER_ADMIN"})
    void preHandle_roleWithoutAdmin_forbidden(String role) {
        HttpServletRequest request = requestWith("user-1", role);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(SettlementException.class)
                .extracting(ex -> ((SettlementException) ex).getErrorCode())
                .isEqualTo(SettlementErrorCode.FORBIDDEN);
    }

    @DisplayName("userId 가 비어있으면 UNAUTHENTICATED")
    @Test
    void preHandle_blankUserId_unauthenticated() {
        HttpServletRequest request = requestWith("  ", "ADMIN");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(SettlementException.class)
                .extracting(ex -> ((SettlementException) ex).getErrorCode())
                .isEqualTo(SettlementErrorCode.UNAUTHENTICATED);
    }

    @DisplayName("role 이 null 이면 UNAUTHENTICATED")
    @Test
    void preHandle_nullRole_unauthenticated() {
        HttpServletRequest request = requestWith("user-1", null);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(SettlementException.class)
                .extracting(ex -> ((SettlementException) ex).getErrorCode())
                .isEqualTo(SettlementErrorCode.UNAUTHENTICATED);
    }

    @DisplayName("단일 ADMIN 은 정상 통과한다(회귀 방지)")
    @Test
    void preHandle_singleAdmin_passes() {
        HttpServletRequest request = requestWith("user-1", "ADMIN");

        assertThatCode(() -> interceptor.preHandle(request, response, handler))
                .doesNotThrowAnyException();
    }
}
