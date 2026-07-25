package com.prompthub.apigateway.logging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.prompthub.apigateway.logging.GatewayLogConstants.EVENT_TYPE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_HEADER;
import static com.prompthub.apigateway.logging.GatewayLogConstants.SERVICE_NAME;
import static com.prompthub.apigateway.logging.GatewayLogConstants.UNKNOWN;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ID_HEADER;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_HEADER;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.fail-fast=false",
        "eureka.client.enabled=false"
})
class GatewayStructuredLoggingIntegrationTest {

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void Security가_먼저_반환한_401도_JSON_access_로그를_한_건_출력한다(CapturedOutput output)
            throws Exception {
        EntityExchangeResult<byte[]> result = client().get()
                .uri("/protected-unmatched")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(REQUEST_ID_HEADER)
                .expectBody()
                .returnResult();

        String requestId = result.getResponseHeaders().getFirst(REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank();
        UUID.fromString(requestId);

        CapturedAccess access = singleAccess(output, requestId);
        JsonNode json = access.json();
        assertThat(json.path("eventType").asString()).isEqualTo(EVENT_TYPE);
        assertThat(json.path("service").asString()).isEqualTo(SERVICE_NAME);
        assertThat(json.path("requestId").asString()).isEqualTo(requestId);
        assertThat(json.path("method").asString()).isEqualTo("GET");
        assertThat(json.path("path").asString()).isEqualTo("/protected-unmatched");
        assertThat(json.path("routeId").asString()).isEqualTo(UNKNOWN);
        assertThat(json.path("status").isNumber()).isTrue();
        assertThat(json.path("status").asInt()).isEqualTo(401);
        assertThat(json.path("durationMs").isNumber()).isTrue();
        assertThat(json.path("authenticated").isBoolean()).isTrue();
        assertThat(json.path("authenticated").asBoolean()).isFalse();
        assertThat(json.path("clientIp").asString()).isNotBlank();
        assertThat(json.path("level").asString()).isEqualTo("WARN");
        assertThat(json.has("userRole")).isFalse();
        assertThat(json.has("exceptionType")).isFalse();
    }

    @Test
    void 인증된_미매칭_404도_민감정보_없는_JSON_access_로그를_한_건_출력한다(
            CapturedOutput output) throws Exception {
        given(reactiveJwtDecoder.decode("valid-token"))
                .willReturn(Mono.just(validJwt()));

        EntityExchangeResult<byte[]> result = client().post()
                .uri("/unmatched?querySecret=query-secret")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .header(USER_ID_HEADER, "forged-user")
                .header(USER_ROLE_HEADER, "ADMIN")
                .cookie("session", "cookie-secret")
                .bodyValue("body-secret")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists(REQUEST_ID_HEADER)
                .expectBody()
                .returnResult();

        String requestId = result.getResponseHeaders().getFirst(REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank();
        UUID.fromString(requestId);

        CapturedAccess access = singleAccess(output, requestId);
        JsonNode json = access.json();
        assertThat(json.path("requestId").asString()).isEqualTo(requestId);
        assertThat(json.path("method").asString()).isEqualTo("POST");
        assertThat(json.path("path").asString()).isEqualTo("/unmatched");
        assertThat(json.path("routeId").asString()).isEqualTo(UNKNOWN);
        assertThat(json.path("status").asInt()).isEqualTo(404);
        assertThat(json.path("authenticated").asBoolean()).isFalse();
        assertThat(json.path("level").asString()).isEqualTo("WARN");
        assertThat(json.path("exceptionType").asString()).isEqualTo("NoResourceFoundException");
        assertThat(access.line()).doesNotContain(
                "query-secret",
                "valid-token",
                "cookie-secret",
                "forged-user",
                "body-secret");
    }

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(applicationContext).build();
    }

    private CapturedAccess singleAccess(CapturedOutput output, String requestId) throws Exception {
        await()
                .atMost(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(10))
                .during(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(accessLines(output, requestId)).hasSize(1));

        String line = accessLines(output, requestId).getFirst();
        return new CapturedAccess(line, new ObjectMapper().readTree(line));
    }

    private List<String> accessLines(CapturedOutput output, String requestId) {
        return output.getOut().lines()
                .filter(line -> line.contains("\"eventType\":\"GATEWAY_ACCESS\""))
                .filter(line -> line.contains("\"requestId\":\"" + requestId + "\""))
                .toList();
    }

    private Jwt validJwt() {
        return Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("user-1")
                .claim("epoch", 1L)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    private record CapturedAccess(String line, JsonNode json) {
    }
}
