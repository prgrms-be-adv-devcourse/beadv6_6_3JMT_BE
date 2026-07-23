package com.prompthub.apigateway.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void 브라우저에_X_Request_Id_응답_헤더를_노출한다() {
        CorsConfigurationSource source = new CorsConfig()
                .corsConfigurationSource(List.of("http://localhost:3000"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/orders")
                        .header("Origin", "http://localhost:3000")
                        .build());

        CorsConfiguration configuration = source.getCorsConfiguration(exchange);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getExposedHeaders()).containsExactly(REQUEST_ID_HEADER);
    }
}
