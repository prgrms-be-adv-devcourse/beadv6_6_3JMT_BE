package com.prompthub.apigateway.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * gateway.api-versions.{service}: [v1, v2] 형태로 서비스별 활성 API 버전 리스트를 바인딩한다.
 * 리스트가 비어있거나 서비스 키가 없으면 해당 서비스는 라우트/화이트리스트에서 제외된다(= 404).
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayApiVersionProperties {

    private Map<String, List<String>> apiVersions = new LinkedHashMap<>();

    public Map<String, List<String>> getApiVersions() {
        return apiVersions;
    }

    public void setApiVersions(Map<String, List<String>> apiVersions) {
        this.apiVersions = apiVersions;
    }

    public List<String> versionsFor(String serviceKey) {
        return apiVersions.getOrDefault(serviceKey, List.of());
    }
}
