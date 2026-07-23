package com.prompthub.apigateway.logging;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import static com.prompthub.apigateway.logging.GatewayLogConstants.AUTHENTICATED_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_HEADER;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ID_HEADER;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_HEADER;

@Component
public class GatewayAccessLogWebFilter implements WebFilter, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(GatewayAccessLogWebFilter.class);

    private final GatewayAccessLogFactory factory;
    private final GatewayAccessLogWriter writer;

    public GatewayAccessLogWebFilter(
            GatewayAccessLogFactory factory,
            GatewayAccessLogWriter writer) {
        this.factory = factory;
        this.writer = writer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isHealthCheck(path)) {
            return chain.filter(exchange);
        }

        String requestId = UUID.randomUUID().toString();
        ServerWebExchange contextualExchange = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(REQUEST_ID_HEADER, requestId);
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLE_HEADER);
                }))
                .build();
        contextualExchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        contextualExchange.getAttributes().put(AUTHENTICATED_ATTRIBUTE, false);
        contextualExchange.getAttributes().remove(USER_ROLE_ATTRIBUTE);
        contextualExchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        contextualExchange.getResponse().beforeCommit(() -> {
            contextualExchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
            return Mono.empty();
        });

        long startedNanos = System.nanoTime();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        return chain.filter(contextualExchange)
                .doOnError(failure::set)
                .doFinally(signal -> writeSafely(
                        contextualExchange,
                        elapsedMillis(startedNanos),
                        failure.get(),
                        signal == SignalType.CANCEL));
    }

    private boolean isHealthCheck(String path) {
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/liveness")
                || path.equals("/readiness");
    }

    private long elapsedMillis(long startedNanos) {
        long elapsedNanos = System.nanoTime() - startedNanos;
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
    }

    private void writeSafely(
            ServerWebExchange exchange,
            long durationMs,
            Throwable failure,
            boolean cancelled) {
        try {
            writer.write(factory.create(exchange, durationMs, failure, cancelled));
        } catch (RuntimeException loggingFailure) {
            log.error("Failed to write gateway access log", loggingFailure);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
