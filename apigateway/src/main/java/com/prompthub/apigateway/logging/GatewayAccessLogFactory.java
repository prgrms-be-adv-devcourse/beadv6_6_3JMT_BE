package com.prompthub.apigateway.logging;

import org.slf4j.event.Level;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;

import com.prompthub.apigateway.client.GatewayRole;
import com.prompthub.apigateway.logging.support.ClientIpResolver;

import static com.prompthub.apigateway.logging.GatewayLogConstants.AUTHENTICATED_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.EVENT_TYPE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.REQUEST_ID_ATTRIBUTE;
import static com.prompthub.apigateway.logging.GatewayLogConstants.SERVICE_NAME;
import static com.prompthub.apigateway.logging.GatewayLogConstants.UNKNOWN;
import static com.prompthub.apigateway.logging.GatewayLogConstants.USER_ROLE_ATTRIBUTE;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class GatewayAccessLogFactory {

    private static final int DEFAULT_SUCCESS_STATUS = 200;
    private static final int ERROR_STATUS = 500;
    private static final int CLIENT_CANCELLED_STATUS = 499;

    private final ClientIpResolver clientIpResolver;

    public GatewayAccessLogFactory(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    public GatewayAccessLog create(
            ServerWebExchange exchange,
            long durationMs,
            Throwable failure,
            boolean cancelled) {
        int status = resolveStatus(exchange, failure, cancelled);
        return new GatewayAccessLog(
                EVENT_TYPE,
                SERVICE_NAME,
                attributeOrUnknown(exchange, REQUEST_ID_ATTRIBUTE),
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getPath().value(),
                routeId(exchange),
                status,
                Math.max(0L, durationMs),
                Boolean.TRUE.equals(exchange.getAttribute(AUTHENTICATED_ATTRIBUTE)),
                exchange.getAttribute(USER_ROLE_ATTRIBUTE),
                clientIpResolver.resolve(exchange),
                failure == null ? null : failure.getClass().getSimpleName(),
                level(status, failure, cancelled)
        );
    }

    private int resolveStatus(ServerWebExchange exchange, Throwable failure, boolean cancelled) {
        if (cancelled) {
            return CLIENT_CANCELLED_STATUS;
        }
        if (failure instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode().value();
        }
        if (failure != null) {
            return ERROR_STATUS;
        }
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        return statusCode == null ? DEFAULT_SUCCESS_STATUS : statusCode.value();
    }

    private Level level(int status, Throwable failure, boolean cancelled) {
        if (cancelled) {
            return Level.WARN;
        }
        if (failure != null && !(failure instanceof ErrorResponse) || status >= 500) {
            return Level.ERROR;
        }
        if (status >= 400) {
            return Level.WARN;
        }
        return Level.INFO;
    }

    private String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return route == null ? UNKNOWN : route.getId();
    }

    private String attributeOrUnknown(ServerWebExchange exchange, String key) {
        String value = exchange.getAttribute(key);
        return value == null ? UNKNOWN : value;
    }
}
