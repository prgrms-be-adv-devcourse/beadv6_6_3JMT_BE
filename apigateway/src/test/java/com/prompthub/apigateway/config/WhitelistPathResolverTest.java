package com.prompthub.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WhitelistPathResolverTest {

    private static GatewayApiVersionProperties propertiesOf(Map<String, List<String>> apiVersions) {
        GatewayApiVersionProperties properties = new GatewayApiVersionProperties();
        properties.setApiVersions(apiVersions);
        return properties;
    }

    @Test
    void v1만_활성화되면_v1_auth_경로만_화이트리스트에_포함된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("user-service", List.of("v1"));

        List<String> whitelist = WhitelistPathResolver.authWhitelist(propertiesOf(config));

        assertThat(whitelist).contains("/api/v1/auth/signup", "/api/v1/auth/login");
        assertThat(whitelist).noneMatch(p -> p.contains("/api/v2/"));
        assertThat(whitelist).contains("/actuator/**");
    }

    @Test
    void user_service가_v1_v2_병행이면_두_버전_auth_경로가_모두_화이트리스트에_포함된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("user-service", List.of("v1", "v2"));

        List<String> whitelist = WhitelistPathResolver.authWhitelist(propertiesOf(config));

        assertThat(whitelist).contains(
            "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/oauth/**", "/api/v1/auth/token/refresh",
            "/api/v2/auth/signup", "/api/v2/auth/login", "/api/v2/auth/oauth/**", "/api/v2/auth/token/refresh"
        );
    }

    @Test
    void user_service_키가_없으면_auth_경로_없이_공통_화이트리스트만_남는다() {
        Map<String, List<String>> config = new LinkedHashMap<>();

        List<String> whitelist = WhitelistPathResolver.authWhitelist(propertiesOf(config));

        assertThat(whitelist).noneMatch(p -> p.contains("/auth/"));
        assertThat(whitelist).contains("/actuator/**", "/swagger-ui.html");
    }

    @Test
    void product_service_v1_v2_병행이면_두_버전_products_조회_경로가_모두_포함된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("product-service", List.of("v1", "v2"));

        List<String> whitelist = WhitelistPathResolver.productReadWhitelist(propertiesOf(config));

        assertThat(whitelist).containsExactlyInAnyOrder(
            "/api/v1/products", "/api/v1/products/**",
            "/api/v2/products", "/api/v2/products/**"
        );
    }

    @Test
    void product_service_키가_없으면_products_화이트리스트가_비어있다() {
        Map<String, List<String>> config = new LinkedHashMap<>();

        List<String> whitelist = WhitelistPathResolver.productReadWhitelist(propertiesOf(config));

        assertThat(whitelist).isEmpty();
    }
}
