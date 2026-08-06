package com.prompthub.apigateway.route;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;

import com.prompthub.apigateway.config.GatewayApiVersionProperties;

import reactor.core.publisher.Flux;

/**
 * gateway.api-versions 설정을 소스로 서비스별 라우트를 생성한다.
 * 활성 버전이 없는 서비스는 라우트 자체를 만들지 않는다 — 비활성 버전 경로는
 * 게이트웨이가 rewrite하지 않고 그냥 404가 되도록 하는 ADR-0007 결정에 따른 것이다.
 */
@Component
public class VersionedRouteDefinitionLocator implements RouteDefinitionLocator {

    private final GatewayApiVersionProperties apiVersionProperties;

    public VersionedRouteDefinitionLocator(GatewayApiVersionProperties apiVersionProperties) {
        this.apiVersionProperties = apiVersionProperties;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(buildRouteDefinitions(apiVersionProperties));
    }

    static List<RouteDefinition> buildRouteDefinitions(GatewayApiVersionProperties apiVersionProperties) {
        List<RouteDefinition> definitions = new ArrayList<>();
        for (VersionedServiceRoute spec : VersionedServiceRoute.ALL) {
            List<String> versions = apiVersionProperties.versionsFor(spec.id());
            if (versions.isEmpty()) {
                continue;
            }
            definitions.add(toRouteDefinition(spec, versions));
        }
        return definitions;
    }

    static List<String> buildPathPatterns(VersionedServiceRoute spec, List<String> versions) {
        List<String> patterns = new ArrayList<>();
        for (String version : versions) {
            for (String suffix : spec.pathSuffixes()) {
                patterns.add("/api/" + version + suffix);
            }
        }
        return patterns;
    }

    private static RouteDefinition toRouteDefinition(VersionedServiceRoute spec, List<String> versions) {
        List<String> patterns = buildPathPatterns(spec, versions);

        RouteDefinition definition = new RouteDefinition();
        definition.setId(spec.id());
        definition.setUri(URI.create(spec.uri()));
        definition.setOrder(spec.order());
        definition.setPredicates(List.of(new PredicateDefinition("Path=" + String.join(",", patterns))));
        return definition;
    }
}
