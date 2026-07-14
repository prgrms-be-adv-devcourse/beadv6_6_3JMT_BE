package com.prompthub.apigateway.client;

import reactor.core.publisher.Mono;

public interface AuthorizeClient {
    Mono<AuthorizeResult> authorize(String userId, long epoch);
}
