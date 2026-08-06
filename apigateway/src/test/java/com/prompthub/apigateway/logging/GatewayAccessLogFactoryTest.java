package com.prompthub.apigateway.logging;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.event.Level;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import com.prompthub.apigateway.client.GatewayRole;
import com.prompthub.apigateway.logging.support.ClientIpResolver;

import static com.prompthub.apigateway.logging.GatewayLogConstants.AUTHENTICATED_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.EVENT_TYPE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.SERVICE_NAME;
import static com.prompthub.apigateway.logging.GatewayLogConstants.UNKNOWN;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_ATTRIBUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class GatewayAccessLogFactoryTest {

    private final GatewayAccessLogFactory factory =
            new GatewayAccessLogFactory(new ClientIpResolver());

    @Test
    void exchange에서_정상_access_로그를_생성한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v2/orders?secret=hidden")
                        .remoteAddress(new InetSocketAddress("192.0.2.20", 8080))
                        .build());
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, "request-1");
        exchange.getAttributes().put(AUTHENTICATED_ATTRIBUTE, true);
        exchange.getAttributes().put(USER_ROLE_ATTRIBUTE, GatewayRole.SELLER);
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async()
                .id("order-service")
                .uri("http://order-service")
                .predicate(ignored -> true)
                .build());
        exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);

        GatewayAccessLog event = factory.create(exchange, 17L, null, false);

        assertThat(event.eventType()).isEqualTo(EVENT_TYPE);
        assertThat(event.service()).isEqualTo(SERVICE_NAME);
        assertThat(event.requestId()).isEqualTo("request-1");
        assertThat(event.method()).isEqualTo("POST");
        assertThat(event.path()).isEqualTo("/api/v2/orders");
        assertThat(event.routeId()).isEqualTo("order-service");
        assertThat(event.status()).isEqualTo(204);
        assertThat(event.durationMs()).isEqualTo(17L);
        assertThat(event.authenticated()).isTrue();
        assertThat(event.userRole()).isEqualTo(GatewayRole.SELLER);
        assertThat(event.clientIp()).isEqualTo("192.0.2.20");
        assertThat(event.exceptionType()).isNull();
        assertThat(event.level()).isEqualTo(Level.INFO);
    }

    @Test
    void 값이_없으면_안전한_기본값을_사용한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/missing").build());

        GatewayAccessLog event = factory.create(exchange, -5L, null, false);

        assertThat(event.requestId()).isEqualTo(UNKNOWN);
        assertThat(event.routeId()).isEqualTo(UNKNOWN);
        assertThat(event.status()).isEqualTo(200);
        assertThat(event.durationMs()).isZero();
        assertThat(event.authenticated()).isFalse();
        assertThat(event.userRole()).isNull();
        assertThat(event.clientIp()).isEqualTo(UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({
            "301, INFO",
            "404, WARN",
            "503, ERROR"
    })
    void 응답_status에_맞는_level을_선택한다(int status, Level expectedLevel) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/status").build());
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));

        GatewayAccessLog event = factory.create(exchange, 1L, null, false);

        assertThat(event.status()).isEqualTo(status);
        assertThat(event.level()).isEqualTo(expectedLevel);
    }

    @Test
    void 예외는_응답_status와_무관하게_500_ERROR로_기록한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/failure").build());
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);

        GatewayAccessLog event =
                factory.create(exchange, 2L, new IllegalStateException("sensitive"), false);

        assertThat(event.status()).isEqualTo(500);
        assertThat(event.level()).isEqualTo(Level.ERROR);
        assertThat(event.exceptionType()).isEqualTo("IllegalStateException");
    }

    @ParameterizedTest
    @CsvSource({
            "404, WARN",
            "503, ERROR"
    })
    void HTTP_status_예외는_응답_status보다_예외_status와_level을_사용한다(
            int status, Level expectedLevel) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/error-response").build());
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);

        GatewayAccessLog event = factory.create(
                exchange,
                2L,
                new ResponseStatusException(HttpStatusCode.valueOf(status)),
                false);

        assertThat(event.status()).isEqualTo(status);
        assertThat(event.level()).isEqualTo(expectedLevel);
        assertThat(event.exceptionType()).isEqualTo("ResponseStatusException");
    }

    @Test
    void 취소는_499_WARN으로_기록한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/cancelled").build());

        GatewayAccessLog event = factory.create(exchange, 3L, null, true);

        assertThat(event.status()).isEqualTo(499);
        assertThat(event.level()).isEqualTo(Level.WARN);
        assertThat(event.exceptionType()).isNull();
    }

    @Test
    void 예외가_있어도_취소는_499_WARN으로_기록한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/cancelled-with-failure").build());

        GatewayAccessLog event =
                factory.create(exchange, 3L, new IllegalStateException("sensitive"), true);

        assertThat(event.status()).isEqualTo(499);
        assertThat(event.level()).isEqualTo(Level.WARN);
        assertThat(event.exceptionType()).isEqualTo("IllegalStateException");
    }

    @Test
    void HTTP_status_예외가_있어도_취소는_499_WARN으로_기록한다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/cancelled-with-error-response").build());

        GatewayAccessLog event = factory.create(
                exchange,
                3L,
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE),
                true);

        assertThat(event.status()).isEqualTo(499);
        assertThat(event.level()).isEqualTo(Level.WARN);
        assertThat(event.exceptionType()).isEqualTo("ResponseStatusException");
    }
}
