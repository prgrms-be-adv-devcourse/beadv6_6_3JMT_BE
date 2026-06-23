package com.prompthub.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class UserHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(ctx -> Mono.justOrEmpty(ctx.getAuthentication()))
            .ofType(JwtAuthenticationToken.class)
            .flatMap(auth -> {
                var jwt = auth.getToken();
                var userId = jwt.getSubject();
                var role = jwt.getClaimAsString("role");

                ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r
                        .headers(headers -> headers.remove(org.springframework.http.HttpHeaders.AUTHORIZATION))
                        .header("X-User-Id", userId)
                        .header("X-User-Role", role))
                    .build();
                return chain.filter(mutated);
            })
            .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
