package com.prompthub.apigateway.filter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.prompthub.apigateway.client.GatewayRole;
import com.prompthub.apigateway.config.GatewayRoutePolicyProperties;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePolicyResolverTest {

    private static GatewayRoutePolicyProperties propertiesOf(Map<String, String> policies) {
        GatewayRoutePolicyProperties properties = new GatewayRoutePolicyProperties();
        properties.setRoutePolicies(policies);
        return properties;
    }

    @Test
    void admin_경로는_설정이_비어있어도_ADMIN_캐치올로_매칭된다() {
        GatewayRoutePolicyProperties properties = propertiesOf(new LinkedHashMap<>());

        Optional<GatewayRole> result = RoutePolicyResolver.requiredRole("/api/v2/admin/users", properties);

        assertThat(result).contains(GatewayRole.ADMIN);
    }

    @Test
    void admin_캐치올은_설정보다_항상_우선한다() {
        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("/api/*/admin/**", "BUYER");
        GatewayRoutePolicyProperties properties = propertiesOf(policies);

        Optional<GatewayRole> result = RoutePolicyResolver.requiredRole("/api/v2/admin/users", properties);

        assertThat(result).contains(GatewayRole.ADMIN);
    }

    @Test
    void 설정된_패턴에_매칭되면_해당_role을_반환한다() {
        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("/api/*/sellers/me/**", "SELLER");
        GatewayRoutePolicyProperties properties = propertiesOf(policies);

        Optional<GatewayRole> result = RoutePolicyResolver.requiredRole("/api/v2/sellers/me/products", properties);

        assertThat(result).contains(GatewayRole.SELLER);
    }

    @Test
    void ai_정산_경로는_SELLER만_접근한다() {
        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("/api/*/ai/settlement/**", "SELLER");

        Optional<GatewayRole> result = RoutePolicyResolver.requiredRole(
            "/api/v2/ai/settlement/conversations/current",
            propertiesOf(policies)
        );

        assertThat(result).contains(GatewayRole.SELLER);
    }

    @Test
    void 선언_순서상_첫_매칭이_우선한다() {
        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("/api/*/sellers/**", "SELLER");
        policies.put("/api/*/sellers/me/**", "BUYER");
        GatewayRoutePolicyProperties properties = propertiesOf(policies);

        Optional<GatewayRole> result = RoutePolicyResolver.requiredRole("/api/v2/sellers/me/products", properties);

        assertThat(result).contains(GatewayRole.SELLER);
    }

    @Test
    void 매칭되는_정책이_없으면_empty를_반환한다() {
        GatewayRoutePolicyProperties properties = propertiesOf(new LinkedHashMap<>());

        Optional<GatewayRole> result = RoutePolicyResolver.requiredRole("/api/v2/products", properties);

        assertThat(result).isEmpty();
    }
}
