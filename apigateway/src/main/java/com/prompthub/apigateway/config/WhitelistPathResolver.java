package com.prompthub.apigateway.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 인증 화이트리스트를 gateway.api-versions 설정으로부터 조합한다.
 * 경로 접미사와 소속 서비스는 코드에 고정하고, 버전 프리픽스만 설정에서 읽는다.
 * 이렇게 하지 않으면 어떤 서비스가 병행(v1+v2) 상태가 됐을 때 v2 로그인/가입 경로가
 * 화이트리스트에 없어 인증을 요구하는 self-lock 버그가 생긴다. (ADR-0007 결정 4)
 */
public final class WhitelistPathResolver {

    private static final String USER_SERVICE_KEY = "user-service";
    private static final String PRODUCT_SERVICE_KEY = "product-service";

    private static final List<String> AUTH_PATH_SUFFIXES = List.of(
        "/auth/oauth/**",
        "/auth/token/refresh"
    );

    private static final List<String> PRODUCT_READ_PATH_SUFFIXES = List.of(
        "/products",
        "/products/*",
        "/products/*/recommends"
    );

    private static final List<String> SELLER_LOOKUP_PATH_SUFFIXES = List.of(
        "/sellers/product",
        "/sellers/products"
    );

    private static final List<String> STATIC_WHITELIST = List.of(
        "/actuator/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/webjars/**",
        "/*/v3/api-docs"
    );

    private WhitelistPathResolver() {
    }

    /** 버전과 무관하게 항상 인증을 우회하는 경로 + 버전별 auth 경로(user-service). */
    public static List<String> authWhitelist(GatewayApiVersionProperties apiVersionProperties) {
        List<String> paths = new ArrayList<>(STATIC_WHITELIST);
        for (String version : apiVersionProperties.versionsFor(USER_SERVICE_KEY)) {
            for (String suffix : AUTH_PATH_SUFFIXES) {
                paths.add("/api/" + version + suffix);
            }
        }
        return paths;
    }

    /** GET으로만 permitAll인 상품 조회 경로(product-service). */
    public static List<String> productReadWhitelist(GatewayApiVersionProperties apiVersionProperties) {
        List<String> paths = new ArrayList<>();
        for (String version : apiVersionProperties.versionsFor(PRODUCT_SERVICE_KEY)) {
            for (String suffix : PRODUCT_READ_PATH_SUFFIXES) {
                paths.add("/api/" + version + suffix);
            }
        }
        return paths;
    }

    /** POST로만 permitAll인 판매자 정보 조회 경로(user-service). */
    public static List<String> sellerLookupWhitelist(GatewayApiVersionProperties apiVersionProperties) {
        List<String> paths = new ArrayList<>();
        for (String version : apiVersionProperties.versionsFor(USER_SERVICE_KEY)) {
            for (String suffix : SELLER_LOOKUP_PATH_SUFFIXES) {
                paths.add("/api/" + version + suffix);
            }
        }
        return paths;
    }
}
