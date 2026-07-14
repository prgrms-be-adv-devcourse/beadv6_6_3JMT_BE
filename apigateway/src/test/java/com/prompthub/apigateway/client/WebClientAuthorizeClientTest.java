package com.prompthub.apigateway.client;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientAuthorizeClientTest {

    private static WebClientAuthorizeClient clientReturning(ClientResponse response) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(response));
        return new WebClientAuthorizeClient(builder);
    }

    @Test
    void _200이면_AuthorizeResult로_매핑한다() {
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"status\":\"ACTIVE\",\"role\":\"BUYER\"}")
                .build();
        WebClientAuthorizeClient client = clientReturning(response);

        StepVerifier.create(client.authorize("user-1", 3L))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo("ACTIVE");
                    assertThat(result.role()).isEqualTo(GatewayRole.BUYER);
                })
                .verifyComplete();
    }

    @Test
    void _401이면_AuthorizeDeniedException() {
        ClientResponse response = ClientResponse.create(HttpStatus.UNAUTHORIZED).build();
        WebClientAuthorizeClient client = clientReturning(response);

        StepVerifier.create(client.authorize("user-1", 3L))
                .expectError(AuthorizeDeniedException.class)
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void _404이면_AuthorizeDeniedException() {
        ClientResponse response = ClientResponse.create(HttpStatus.NOT_FOUND).build();
        WebClientAuthorizeClient client = clientReturning(response);

        StepVerifier.create(client.authorize("user-1", 3L))
                .expectError(AuthorizeDeniedException.class)
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void _500이면_AuthorizeUnavailableException() {
        ClientResponse response = ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build();
        WebClientAuthorizeClient client = clientReturning(response);

        StepVerifier.create(client.authorize("user-1", 3L))
                .expectError(AuthorizeUnavailableException.class)
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void 타임아웃이면_AuthorizeUnavailableException() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.never());
        WebClientAuthorizeClient client = new WebClientAuthorizeClient(builder);

        StepVerifier.create(client.authorize("user-1", 3L))
                .expectError(AuthorizeUnavailableException.class)
                .verify(Duration.ofSeconds(2));
    }
}
