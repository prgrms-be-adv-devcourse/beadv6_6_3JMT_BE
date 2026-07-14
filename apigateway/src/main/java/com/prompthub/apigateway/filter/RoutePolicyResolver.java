package com.prompthub.apigateway.filter;

import java.util.Map;
import java.util.Optional;

import org.springframework.util.AntPathMatcher;

import com.prompthub.apigateway.client.GatewayRole;
import com.prompthub.apigateway.config.GatewayRoutePolicyProperties;

/**
 * 경로별 필요 role을 판정한다. /admin/** 캐치올은 설정과 무관한 코드 기본값이며
 * 항상 최우선으로 확인한다 — 실제 요청 경로는 /api/{version}/... 형태라 버전
 * 세그먼트는 Ant `*`로 흡수한다(VersionedServiceRoute 확인됨).
 */
public final class RoutePolicyResolver {

    private static final String ADMIN_CATCHALL_PATTERN = "/api/*/admin/**";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private RoutePolicyResolver() {
    }

    public static Optional<GatewayRole> requiredRole(String path, GatewayRoutePolicyProperties properties) {
        if (PATH_MATCHER.match(ADMIN_CATCHALL_PATTERN, path)) {
            return Optional.of(GatewayRole.ADMIN);
        }

        for (Map.Entry<String, String> entry : properties.getRoutePolicies().entrySet()) {
            if (PATH_MATCHER.match(entry.getKey(), path)) {
                return Optional.of(GatewayRole.valueOf(entry.getValue()));
            }
        }

        return Optional.empty();
    }
}
