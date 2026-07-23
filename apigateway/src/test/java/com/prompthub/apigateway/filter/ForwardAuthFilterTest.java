package com.prompthub.apigateway.filter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

import com.prompthub.apigateway.client.AuthorizeClient;
import com.prompthub.apigateway.client.AuthorizeDeniedException;
import com.prompthub.apigateway.client.AuthorizeResult;
import com.prompthub.apigateway.client.AuthorizeUnavailableException;
import com.prompthub.apigateway.client.GatewayRole;
import com.prompthub.apigateway.config.GatewayRoutePolicyProperties;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.prompthub.apigateway.logging.GatewayLogConstants.AUTHENTICATED_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ID_HEADER;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ForwardAuthFilterTest {

    @Mock
    private AuthorizeClient authorizeClient;

    private static GatewayRoutePolicyProperties emptyPolicies() {
        GatewayRoutePolicyProperties properties = new GatewayRoutePolicyProperties();
        properties.setRoutePolicies(new LinkedHashMap<>());
        return properties;
    }

    private static Jwt jwtWithEpoch(String userId, Long epoch) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900));
        if (epoch != null) {
            builder.claim("epoch", epoch);
        }
        return builder.build();
    }

    private static GatewayFilterChain capturingChain(AtomicReference<ServerWebExchange> captured) {
        return exchange -> {
            captured.set(exchange);
            return Mono.empty();
        };
    }

    private static Mono<Void> runFilter(ForwardAuthFilter filter, ServerWebExchange exchange,
                                         GatewayFilterChain chain, Jwt jwt) {
        exchange.getAttributes().put(AUTHENTICATED_ATTRIBUTE, false);
        exchange.getAttributes().remove(USER_ROLE_ATTRIBUTE);
        SecurityContext securityContext = new SecurityContextImpl(new JwtAuthenticationToken(jwt));
        return filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
    }

    private static void assertUnauthenticated(ServerWebExchange exchange) {
        assertThat(exchange.<Boolean>getAttribute(AUTHENTICATED_ATTRIBUTE)).isEqualTo(false);
        assertThat(exchange.<GatewayRole>getAttribute(USER_ROLE_ATTRIBUTE)).isNull();
    }

    @Test
    void 익명_요청도_신뢰하지_않는_헤더를_다운스트림에_전달하지_않는다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer external")
                        .header(USER_ID_HEADER, "forged-user")
                        .header(USER_ROLE_HEADER, "ADMIN")
                        .build());
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, capturingChain(captured)))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getRequest().getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(captured.get().getRequest().getHeaders().containsHeader(USER_ID_HEADER)).isFalse();
        assertThat(captured.get().getRequest().getHeaders().containsHeader(USER_ROLE_HEADER)).isFalse();
    }

    @Test
    void epoch_클레임_없으면_401이고_인증_기본값을_유지한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/users/me"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(runFilter(filter, exchange, capturingChain(captured), jwtWithEpoch("user-1", null)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(captured.get()).isNull();
        assertUnauthenticated(exchange);
    }

    @Test
    void authorize가_401이나_404를_던지면_401과_인증_기본값을_유지한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/users/me"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.error(new AuthorizeDeniedException()));

        StepVerifier.create(runFilter(
                filter,
                exchange,
                capturingChain(new AtomicReference<>()),
                jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUnauthenticated(exchange);
    }

    @Test
    void authorize가_타임아웃_5xx면_503과_인증_기본값을_유지한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/users/me"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.error(new AuthorizeUnavailableException(new RuntimeException("boom"))));

        StepVerifier.create(runFilter(
                filter,
                exchange,
                capturingChain(new AtomicReference<>()),
                jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertUnauthenticated(exchange);
    }

    @Test
    void status가_ACTIVE가_아니면_403과_인증_기본값을_유지한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/users/me"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.just(new AuthorizeResult("BLOCKED", GatewayRole.BUYER)));

        StepVerifier.create(runFilter(
                filter,
                exchange,
                capturingChain(new AtomicReference<>()),
                jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertUnauthenticated(exchange);
    }

    @Test
    void 정책표_role_미달이면_403이지만_인증_성공_상태를_유지한다() {
        GatewayRoutePolicyProperties properties = emptyPolicies();
        properties.setRoutePolicies(new LinkedHashMap<>(Map.of("/api/*/admin/**", "ADMIN")));
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/admin/users"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.just(new AuthorizeResult("ACTIVE", GatewayRole.BUYER)));

        StepVerifier.create(runFilter(
                filter,
                exchange,
                capturingChain(new AtomicReference<>()),
                jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.<Boolean>getAttribute(AUTHENTICATED_ATTRIBUTE)).isEqualTo(true);
        assertThat(exchange.<GatewayRole>getAttribute(USER_ROLE_ATTRIBUTE)).isEqualTo(GatewayRole.BUYER);
    }

    @Test
    void 정상이면_위조_헤더를_신뢰값으로_덮어쓰고_인증_상태를_저장한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .header(USER_ID_HEADER, "forged-user")
                        .header(USER_ROLE_HEADER, "ADMIN"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.just(new AuthorizeResult("ACTIVE", GatewayRole.SELLER)));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(runFilter(
                filter,
                exchange,
                capturingChain(captured),
                jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        ServerWebExchange downstream = captured.get();
        assertThat(downstream).isNotNull();
        assertThat(downstream.getRequest().getHeaders().get(USER_ID_HEADER))
                .containsExactly("user-1");
        assertThat(downstream.getRequest().getHeaders().get(USER_ROLE_HEADER))
                .containsExactly("SELLER");
        assertThat(downstream.getRequest().getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(exchange.<Boolean>getAttribute(AUTHENTICATED_ATTRIBUTE)).isEqualTo(true);
        assertThat(exchange.<GatewayRole>getAttribute(USER_ROLE_ATTRIBUTE)).isEqualTo(GatewayRole.SELLER);
    }
}
