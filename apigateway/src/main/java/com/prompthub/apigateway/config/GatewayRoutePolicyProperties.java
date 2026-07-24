package com.prompthub.apigateway.config;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * gateway.route-policies: {경로 패턴: 접근 정책} 형태로 경로별 허용 role을 바인딩한다.
 * GatewayApiVersionProperties와 동일 패턴 — LinkedHashMap으로 선언 순서를 보존해
 * RoutePolicyResolver가 순서대로(첫 매칭 우선) 매칭할 수 있게 한다.
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayRoutePolicyProperties {

    private Map<String, String> routePolicies = new LinkedHashMap<>();

    public Map<String, String> getRoutePolicies() {
        return routePolicies;
    }

    public void setRoutePolicies(Map<String, String> routePolicies) {
        this.routePolicies = routePolicies;
    }

    @PostConstruct
    public void validate() {
        for (String policy : routePolicies.values()) {
            GatewayRouteAccessPolicy.valueOf(policy);
        }
    }
}
