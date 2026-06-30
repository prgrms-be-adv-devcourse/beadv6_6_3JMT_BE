package com.prompthub.settlement.global.web;

import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(AuthHeaders.USER_ID);
        String role = request.getHeader(AuthHeaders.USER_ROLE);

        if (isBlank(userId) || isBlank(role)) {
            throw new SettlementException(SettlementErrorCode.UNAUTHENTICATED);
        }
        if (!hasAdminRole(role)) {
            throw new SettlementException(SettlementErrorCode.FORBIDDEN);
        }
        return true;
    }

    private boolean hasAdminRole(String role) {
        return Arrays.stream(role.split(","))
                .map(String::trim)
                .anyMatch(AuthHeaders.ADMIN_ROLE::equals);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
