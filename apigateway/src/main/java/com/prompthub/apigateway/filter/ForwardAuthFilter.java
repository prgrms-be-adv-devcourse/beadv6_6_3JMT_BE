package com.prompthub.apigateway.filter;

import java.util.Optional;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.prompthub.apigateway.client.AuthorizeClient;
import com.prompthub.apigateway.client.AuthorizeDeniedException;
import com.prompthub.apigateway.client.AuthorizeResult;
import com.prompthub.apigateway.client.AuthorizeUnavailableException;
import com.prompthub.apigateway.client.GatewayRole;
import com.prompthub.apigateway.config.GatewayRoutePolicyProperties;

import reactor.core.publisher.Mono;

@Component
public class ForwardAuthFilter implements GlobalFilter, Ordered {

    private final AuthorizeClient authorizeClient;
    private final GatewayRoutePolicyProperties routePolicyProperties;

    public ForwardAuthFilter(AuthorizeClient authorizeClient, GatewayRoutePolicyProperties routePolicyProperties) {
        this.authorizeClient = authorizeClient;
        this.routePolicyProperties = routePolicyProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> Mono.justOrEmpty(ctx.getAuthentication()))
                .ofType(JwtAuthenticationToken.class)
                .flatMap(auth -> authorizeAndForward(auth.getToken(), exchange, chain))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(true)))
                .then();
    }

    private Mono<Boolean> authorizeAndForward(Jwt jwt, ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = jwt.getSubject();
        Long epoch = extractEpoch(jwt);

        if (epoch == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        return authorizeClient.authorize(userId, epoch)
                .flatMap(result -> proceedOrReject(result, userId, exchange, chain))
                .onErrorResume(AuthorizeDeniedException.class, e -> reject(exchange, HttpStatus.UNAUTHORIZED))
                .onErrorResume(AuthorizeUnavailableException.class, e -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private Mono<Boolean> proceedOrReject(AuthorizeResult result, String userId,
                                           ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!"ACTIVE".equals(result.status())) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }

        String path = exchange.getRequest().getPath().value();
        Optional<GatewayRole> requiredRole = RoutePolicyResolver.requiredRole(path, routePolicyProperties);
        if (requiredRole.isPresent() && !hasRequiredRole(result.role(), requiredRole.get())) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r
                        .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                        .header("X-User-Id", userId)
                        .header("X-User-Role", result.role().name()))
                .build();
        return chain.filter(mutated).thenReturn(true);
    }

    private boolean hasRequiredRole(GatewayRole actualRole, GatewayRole requiredRole) {
        if (requiredRole == GatewayRole.SELLER) {
            return actualRole == GatewayRole.SELLER;
        }
        return actualRole.ordinal() >= requiredRole.ordinal();
    }

    private Long extractEpoch(Jwt jwt) {
        Object claim = jwt.getClaim("epoch");
        return claim instanceof Number number ? number.longValue() : null;
    }

    private Mono<Boolean> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete().thenReturn(true);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
