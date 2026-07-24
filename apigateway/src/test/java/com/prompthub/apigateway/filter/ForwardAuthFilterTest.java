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
        SecurityContext securityContext = new SecurityContextImpl(new JwtAuthenticationToken(jwt));
        return filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
    }

    @Test
    void epoch_클레임_없으면_401이고_authorize를_호출하지_않는다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v2/users/me"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(runFilter(filter, exchange, capturingChain(captured), jwtWithEpoch("user-1", null)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(captured.get()).isNull();
    }

    @Test
    void authorize가_401이나_404를_던지면_401을_반환한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v2/users/me"));
        given(authorizeClient.authorize("user-1", 3L)).willReturn(Mono.error(new AuthorizeDeniedException()));

        StepVerifier.create(runFilter(filter, exchange, capturingChain(new AtomicReference<>()), jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authorize가_타임아웃_5xx면_503을_반환한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v2/users/me"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.error(new AuthorizeUnavailableException(new RuntimeException("boom"))));

        StepVerifier.create(runFilter(filter, exchange, capturingChain(new AtomicReference<>()), jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void status가_ACTIVE가_아니면_403을_반환한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v2/users/me"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.just(new AuthorizeResult("BLOCKED", GatewayRole.BUYER)));

        StepVerifier.create(runFilter(filter, exchange, capturingChain(new AtomicReference<>()), jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 정책표_role_미달이면_403을_반환한다() {
        GatewayRoutePolicyProperties properties = emptyPolicies();
        properties.setRoutePolicies(new LinkedHashMap<>(Map.of("/api/*/admin/**", "ADMIN")));
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v2/admin/users"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.just(new AuthorizeResult("ACTIVE", GatewayRole.BUYER)));

        StepVerifier.create(runFilter(filter, exchange, capturingChain(new AtomicReference<>()), jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 셀러_전용_AI_정산_경로는_ADMIN에게도_403을_반환한다() {
        GatewayRoutePolicyProperties properties = emptyPolicies();
        properties.setRoutePolicies(new LinkedHashMap<>(Map.of("/api/*/ai/settlement/**", "SELLER")));
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/ai/settlement/conversations/current"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.just(new AuthorizeResult("ACTIVE", GatewayRole.ADMIN)));

        StepVerifier.create(runFilter(filter, exchange, capturingChain(captured), jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(captured.get()).isNull();
    }

    @Test
    void 정상이면_헤더를_주입하고_다운스트림으로_전달한다() {
        ForwardAuthFilter filter = new ForwardAuthFilter(authorizeClient, emptyPolicies());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/users/me").header("Authorization", "Bearer token"));
        given(authorizeClient.authorize("user-1", 3L))
                .willReturn(Mono.just(new AuthorizeResult("ACTIVE", GatewayRole.SELLER)));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(runFilter(filter, exchange, capturingChain(captured), jwtWithEpoch("user-1", 3L)))
                .verifyComplete();

        ServerWebExchange downstream = captured.get();
        assertThat(downstream).isNotNull();
        assertThat(downstream.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("user-1");
        assertThat(downstream.getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("SELLER");
        assertThat(downstream.getRequest().getHeaders().containsHeader("Authorization")).isFalse();
    }
}
