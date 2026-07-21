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

        assertThat(whitelist).contains("/api/v1/auth/oauth/**", "/api/v1/auth/token/refresh");
        assertThat(whitelist).noneMatch(p -> p.contains("/api/v2/"));
        assertThat(whitelist).contains("/actuator/**");
    }

    @Test
    void user_service가_v1_v2_병행이면_두_버전_auth_경로가_모두_화이트리스트에_포함된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("user-service", List.of("v1", "v2"));

        List<String> whitelist = WhitelistPathResolver.authWhitelist(propertiesOf(config));

        assertThat(whitelist).contains(
            "/api/v1/auth/oauth/**", "/api/v1/auth/token/refresh",
            "/api/v2/auth/oauth/**", "/api/v2/auth/token/refresh"
        );
    }

    @Test
    void oauth_전용_전환으로_signup_login은_화이트리스트에_없다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("user-service", List.of("v1", "v2"));

        List<String> whitelist = WhitelistPathResolver.authWhitelist(propertiesOf(config));

        assertThat(whitelist).noneMatch(p -> p.contains("/auth/signup") || p.contains("/auth/login"));
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
            "/api/v1/products", "/api/v1/products/*", "/api/v1/products/*/recommends",
            "/api/v2/products", "/api/v2/products/*", "/api/v2/products/*/recommends"
        );
    }

    @Test
    void product_service_키가_없으면_products_화이트리스트가_비어있다() {
        Map<String, List<String>> config = new LinkedHashMap<>();

        List<String> whitelist = WhitelistPathResolver.productReadWhitelist(propertiesOf(config));

        assertThat(whitelist).isEmpty();
    }

    @Test
    void products_와일드카드가_판매자_전용_조회_경로까지_permitAll로_잘못_열어주지_않는다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("product-service", List.of("v2"));

        List<String> whitelist = WhitelistPathResolver.productReadWhitelist(propertiesOf(config));

        assertThat(whitelist).noneMatch(p -> p.contains("/sellers/"));
    }

    @Test
    void user_service_v2_활성화면_판매자_배치_조회_경로가_화이트리스트에_포함된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("user-service", List.of("v2"));

        List<String> whitelist = WhitelistPathResolver.sellerLookupWhitelist(propertiesOf(config));

        assertThat(whitelist).containsExactlyInAnyOrder("/api/v2/sellers/products");
    }

    @Test
    void user_service_v2_활성화면_판매자_단건_조회_경로가_화이트리스트에_포함된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("user-service", List.of("v2"));

        List<String> whitelist = WhitelistPathResolver.sellerSingleLookupWhitelist(propertiesOf(config));

        assertThat(whitelist).containsExactlyInAnyOrder("/api/v2/sellers/product");
    }
}
