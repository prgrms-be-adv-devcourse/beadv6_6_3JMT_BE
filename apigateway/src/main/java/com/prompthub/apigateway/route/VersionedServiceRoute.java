package com.prompthub.apigateway.route;

import java.util.List;

/**
 * 서비스별 라우트 스펙(경로 접미사, 다운스트림 uri, 우선순위)은 코드에 고정한다.
 * 버전 프리픽스(/api/v1, /api/v2 ...)만 {@link com.prompthub.apigateway.config.GatewayApiVersionProperties}
 * 설정에서 읽어 조합한다.
 * <p>
 * order는 낮을수록 우선순위가 높다. 명시적인 order로 Map/YAML 순회 순서와
 * 무관하게 서비스 라우트 우선순위를 보존한다.
 */
public record VersionedServiceRoute(String id, String uri, List<String> pathSuffixes, int order) {

    public static final List<VersionedServiceRoute> ALL = List.of(
        new VersionedServiceRoute(
            "admin-service",
            "lb://ADMIN-SERVICE",
            List.of(
                "/admin/settlements/**", "/admin/orders", "/admin/orders/**",
                "/admin/users", "/admin/users/**", "/admin/stats/users",
                "/admin/sellers/register", "/admin/sellers/register/**",
                "/admin/products", "/admin/products/**", "/admin/home"
            ),
            0
        ),
        new VersionedServiceRoute(
            "order-service",
            "lb://ORDER-SERVICE",
            List.of(
                "/orders", "/orders/**",
                "/cart", "/cart/**"
            ),
            1
        ),
        new VersionedServiceRoute(
            "product-service",
            "lb://PRODUCT-SERVICE",
            List.of("/products", "/products/**"),
            2
        ),
        new VersionedServiceRoute(
            "payment-service",
            "lb://PAYMENT-SERVICE",
            List.of("/payments/**"),
            3
        ),
        new VersionedServiceRoute(
            "user-service",
            "lb://USER-SERVICE",
            List.of(
                "/auth/**", "/users/**",
                "/seller/**", "/sellers/**",
                "/wishlists/**"
            ),
            4
        ),
        new VersionedServiceRoute(
            "ai-service",
            "lb://AI-SERVICE",
            List.of("/ai/settlement/**"),
            5
        ),
        new VersionedServiceRoute(
            "notification-service",
            "lb://NOTIFICATION-SERVICE",
            List.of("/notifications", "/notifications/**"),
            6
        )
    );

    public VersionedServiceRoute {
        pathSuffixes = List.copyOf(pathSuffixes);
    }
}
