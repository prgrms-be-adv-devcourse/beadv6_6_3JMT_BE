package com.prompthub.apigateway.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayRoutePolicyPropertiesTest {

    private static GatewayRoutePolicyProperties propertiesOf(Map<String, String> policies) {
        GatewayRoutePolicyProperties properties = new GatewayRoutePolicyProperties();
        properties.setRoutePolicies(policies);
        return properties;
    }

    @Test
    void role_문자열이_모두_유효하면_예외없이_끝난다() {
        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("/api/*/sellers/me/**", "SELLER");

        propertiesOf(policies).validate();
    }

    @Test
    void 잘못된_role_문자열이면_기동_실패() {
        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("/api/*/sellers/me/**", "NOT_A_ROLE");

        assertThatThrownBy(() -> propertiesOf(policies).validate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기본값은_빈_맵이라_검증을_통과한다() {
        GatewayRoutePolicyProperties properties = new GatewayRoutePolicyProperties();

        assertThat(properties.getRoutePolicies()).isEmpty();
        properties.validate();
    }
}
