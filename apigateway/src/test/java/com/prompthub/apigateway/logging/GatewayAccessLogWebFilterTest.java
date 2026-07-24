package com.prompthub.apigateway.logging;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import com.prompthub.apigateway.logging.support.ClientIpResolver;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import static com.prompthub.apigateway.logging.GatewayLogConstants.AUTHENTICATED_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_HEADER;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ID_HEADER;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GatewayAccessLogWebFilterTest {

    @Mock
    private GatewayAccessLogWriter writer;

    private GatewayAccessLogWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GatewayAccessLogWebFilter(
                new GatewayAccessLogFactory(new ClientIpResolver()),
                writer);
    }

    @Test
    void Security보다_먼저_실행되도록_최우선_순서를_사용한다() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void 외부_ID와_사용자_헤더를_교체하고_요청당_한_건을_기록한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/orders?secret=query-secret")
                        .header(REQUEST_ID_HEADER, "external-request-id")
                        .header(USER_ID_HEADER, "forged-user")
                        .header(USER_ROLE_HEADER, "ADMIN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-secret")
                        .build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, filtered -> {
            downstream.set(filtered);
            filtered.getResponse().setStatusCode(HttpStatus.ACCEPTED);
            return filtered.getResponse().setComplete();
        })).verifyComplete();

        ServerWebExchange forwarded = downstream.get();
        String requestId = forwarded.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        UUID.fromString(requestId);
        assertThat(requestId).isNotEqualTo("external-request-id");
        assertThat(forwarded.getRequest().getHeaders().get(REQUEST_ID_HEADER))
                .containsExactly(requestId);
        assertThat(forwarded.<String>getAttribute(REQUEST_ID_ATTRIBUTE)).isEqualTo(requestId);
        assertThat(forwarded.<Boolean>getAttribute(AUTHENTICATED_ATTRIBUTE)).isEqualTo(false);
        assertThat(forwarded.<Object>getAttribute(USER_ROLE_ATTRIBUTE)).isNull();
        assertThat(forwarded.getRequest().getHeaders().containsHeader(USER_ID_HEADER)).isFalse();
        assertThat(forwarded.getRequest().getHeaders().containsHeader(USER_ROLE_HEADER)).isFalse();
        assertThat(forwarded.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer token-secret");
        assertThat(exchange.getResponse().getHeaders().getFirst(REQUEST_ID_HEADER))
                .isEqualTo(requestId);

        ArgumentCaptor<GatewayAccessLog> event = ArgumentCaptor.forClass(GatewayAccessLog.class);
        verify(writer, times(1)).write(event.capture());
        assertThat(event.getValue().requestId()).isEqualTo(requestId);
        assertThat(event.getValue().status()).isEqualTo(202);
        assertThat(event.getValue().path()).isEqualTo("/api/v2/orders");
    }

    @Test
    void downstream_응답_ID가_추가되어도_Gateway_ID_하나만_응답한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/orders").build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, filtered -> {
            downstream.set(filtered);
            filtered.getResponse().getHeaders()
                    .add(REQUEST_ID_HEADER, "downstream-request-id");
            return filtered.getResponse().setComplete();
        })).verifyComplete();

        String requestId = downstream.get().getRequest().getHeaders()
                .getFirst(REQUEST_ID_HEADER);
        UUID.fromString(requestId);
        assertThat(exchange.getResponse().getHeaders().get(REQUEST_ID_HEADER))
                .containsExactly(requestId);
    }

    @Test
    void downstream_예외를_그대로_전파하고_500_로그를_한_건_기록한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/failure").build());
        IllegalStateException failure = new IllegalStateException("boom");

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(failure)))
                .expectErrorMatches(error -> error == failure)
                .verify();

        ArgumentCaptor<GatewayAccessLog> event = ArgumentCaptor.forClass(GatewayAccessLog.class);
        verify(writer, times(1)).write(event.capture());
        assertThat(event.getValue().status()).isEqualTo(500);
        assertThat(event.getValue().exceptionType()).isEqualTo("IllegalStateException");
    }

    @Test
    void 취소를_유지하고_499_로그를_한_건_기록한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/cancelled").build());
        TestPublisher<Void> downstream = TestPublisher.create();

        StepVerifier.create(filter.filter(exchange, ignored -> downstream.mono()))
                .thenCancel()
                .verify();

        downstream.assertCancelled();
        ArgumentCaptor<GatewayAccessLog> event = ArgumentCaptor.forClass(GatewayAccessLog.class);
        verify(writer, times(1)).write(event.capture());
        assertThat(event.getValue().status()).isEqualTo(499);
    }

    @Test
    void writer_실패가_정상_요청을_실패시키지_않는다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ok").build());
        willThrow(new IllegalStateException("writer failed"))
                .given(writer)
                .write(any(GatewayAccessLog.class));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty()))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/readiness",
            "/actuator/health/liveness",
            "/liveness",
            "/readiness"
    })
    void 헬스_체크는_요청_ID와_access_로그를_생성하지_않는다(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path).build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, filtered -> {
            downstream.set(filtered);
            return Mono.empty();
        })).verifyComplete();

        assertThat(downstream.get()).isSameAs(exchange);
        assertThat(exchange.getRequest().getHeaders().containsHeader(REQUEST_ID_HEADER)).isFalse();
        assertThat(exchange.getResponse().getHeaders().containsHeader(REQUEST_ID_HEADER)).isFalse();
        verify(writer, never()).write(any(GatewayAccessLog.class));
    }
}
