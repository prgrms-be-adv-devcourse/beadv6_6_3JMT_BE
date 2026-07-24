package com.prompthub.apigateway.logging.support;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void XFF의_마지막_유효_IP를_선택한다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "203.0.113.10, 10.0.0.4")
                        .remoteAddress(new InetSocketAddress("192.0.2.50", 8080)));

        assertThat(resolver.resolve(exchange)).isEqualTo("10.0.0.4");
    }

    @Test
    void 오른쪽의_잘못된_XFF_값을_건너뛴다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "203.0.113.10, 300.0.0.1, unknown, invalid.example")
                        .remoteAddress(new InetSocketAddress("192.0.2.50", 8080)));

        assertThat(resolver.resolve(exchange)).isEqualTo("203.0.113.10");
    }

    @Test
    void IPv6_literal을_선택한다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "198.51.100.10, 2001:db8::7"));

        assertThat(resolver.resolve(exchange)).isEqualTo("2001:db8::7");
    }

    @Test
    void 여러_XFF_헤더_값을_오른쪽부터_순회한다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "198.51.100.10, invalid.example")
                        .header("X-Forwarded-For", "unknown, 2001:db8::8"));

        assertThat(resolver.resolve(exchange)).isEqualTo("2001:db8::8");
    }

    @Test
    void 압축_구간_밖에_빈_그룹이_있는_잘못된_IPv6_literal을_건너뛴다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "2001:db8::7, 1::2:"));

        assertThat(resolver.resolve(exchange)).isEqualTo("2001:db8::7");
    }

    @Test
    void 아라비아_인도_숫자로_된_IPv4_literal을_건너뛴다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "203.0.113.10, ١٩٢.٠.٢.١"));

        assertThat(resolver.resolve(exchange)).isEqualTo("203.0.113.10");
    }

    @Test
    void 아라비아_인도_숫자_IPv4_tail을_가진_IPv6_literal을_건너뛴다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "2001:db8::7, 2001:db8::١٩٢.٠.٢.١"));

        assertThat(resolver.resolve(exchange)).isEqualTo("2001:db8::7");
    }

    @Test
    void 유효한_XFF가_없으면_remote_address를_사용한다() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/")
                        .header("X-Forwarded-For", "unknown, invalid.example")
                        .remoteAddress(new InetSocketAddress("192.0.2.44", 8080)));

        assertThat(resolver.resolve(exchange)).isEqualTo("192.0.2.44");
    }

    @Test
    void XFF와_remote_address가_없으면_unknown을_반환한다() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/"));

        assertThat(resolver.resolve(exchange)).isEqualTo("unknown");
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.build());
    }
}
