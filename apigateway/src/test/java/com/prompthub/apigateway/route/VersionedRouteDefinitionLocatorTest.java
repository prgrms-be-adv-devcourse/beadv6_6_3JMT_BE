package com.prompthub.apigateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.cloud.gateway.route.RouteDefinition;

import com.prompthub.apigateway.config.GatewayApiVersionProperties;

class VersionedRouteDefinitionLocatorTest {

    private static GatewayApiVersionProperties propertiesOf(Map<String, List<String>> apiVersions) {
        GatewayApiVersionProperties properties = new GatewayApiVersionProperties();
        properties.setApiVersions(apiVersions);
        return properties;
    }

    private static RouteDefinition routeById(List<RouteDefinition> definitions, String id) {
        return definitions.stream()
            .filter(d -> d.getId().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("route not found: " + id));
    }

    private static String pathPredicateValue(RouteDefinition definition) {
        return String.join(",", definition.getPredicates().get(0).getArgs().values());
    }

    @Test
    void v1만_활성화되면_v1_경로만_생성된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("order-service", List.of("v1"));

        List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

        RouteDefinition orderRoute = routeById(definitions, "order-service");
        String pattern = pathPredicateValue(orderRoute);
        assertThat(pattern).contains("/api/v1/orders");
        assertThat(pattern).doesNotContain("/api/v2/");
        assertThat(orderRoute.getUri().toString()).isEqualTo("lb://ORDER-SERVICE");
    }

    @Test
    void v1_v2_병행_설정시_두_버전_경로가_모두_생성된다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("order-service", List.of("v1", "v2"));

        List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

        RouteDefinition orderRoute = routeById(definitions, "order-service");
        String pattern = pathPredicateValue(orderRoute);
        assertThat(pattern).contains("/api/v1/orders");
        assertThat(pattern).contains("/api/v2/orders");
    }

    @Test
    void 서비스_키가_없으면_라우트가_생성되지_않는다_즉_404() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("order-service", List.of("v1"));
        // product-service 키를 아예 넣지 않음

        List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

        assertThat(definitions).extracting(RouteDefinition::getId).doesNotContain("product-service");
    }

    @Test
    void 버전_리스트가_빈_배열이면_라우트가_생성되지_않는다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("payment-service", List.of());

        List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

        assertThat(definitions).extracting(RouteDefinition::getId).doesNotContain("payment-service");
    }

    @Test
    void 전체_서비스_기본_설정에_settlement_service_라우트가_없다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        for (VersionedServiceRoute spec : VersionedServiceRoute.ALL) {
            config.put(spec.id(), List.of("v1"));
        }
        config.put("settlement-service", List.of("v1", "v2"));

        List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

        assertThat(definitions).hasSize(5);
        assertThat(definitions).extracting(RouteDefinition::getId)
            .doesNotContain("settlement-service");
    }

    @Test
    void 어드민_주문_경로는_admin_service가_소유하고_order_service는_소유하지_않는다() {
        Map<String, List<String>> config = new LinkedHashMap<>();
        config.put("admin-service", List.of("v2"));
        config.put("order-service", List.of("v1", "v2"));

        List<RouteDefinition> definitions = VersionedRouteDefinitionLocator.buildRouteDefinitions(propertiesOf(config));

        String adminPattern = pathPredicateValue(routeById(definitions, "admin-service"));
        String orderPattern = pathPredicateValue(routeById(definitions, "order-service"));
        assertThat(adminPattern).contains("/api/v2/admin/orders");
        assertThat(orderPattern).doesNotContain("/admin/orders");
    }
}
