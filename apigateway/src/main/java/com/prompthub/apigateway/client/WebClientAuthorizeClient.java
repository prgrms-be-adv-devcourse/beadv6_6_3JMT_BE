package com.prompthub.apigateway.client;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

@Component
public class WebClientAuthorizeClient implements AuthorizeClient {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final WebClient webClient;

    public WebClientAuthorizeClient(WebClient.Builder loadBalancedWebClientBuilder) {
        this.webClient = loadBalancedWebClientBuilder.baseUrl("lb://USER-SERVICE").build();
    }

    @Override
    public Mono<AuthorizeResult> authorize(String userId, long epoch) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/authorize/{userId}")
                        .queryParam("epoch", epoch)
                        .build(userId))
                .retrieve()
                .bodyToMono(AuthorizeApiResponse.class)
                .map(response -> new AuthorizeResult(response.status(), GatewayRole.valueOf(response.role())))
                .timeout(TIMEOUT)
                .onErrorMap(WebClientResponseException.class, this::mapResponseException)
                .onErrorMap(this::isUnmapped, AuthorizeUnavailableException::new);
    }

    private boolean isAlreadyMapped(Throwable error) {
        return error instanceof AuthorizeDeniedException || error instanceof AuthorizeUnavailableException;
    }

    private boolean isUnmapped(Throwable error) {
        return !isAlreadyMapped(error);
    }

    private Throwable mapResponseException(WebClientResponseException error) {
        int statusCode = error.getStatusCode().value();
        if (statusCode == 401 || statusCode == 404) {
            return new AuthorizeDeniedException();
        }
        return new AuthorizeUnavailableException(error);
    }

    private record AuthorizeApiResponse(String status, String role) {
    }
}
