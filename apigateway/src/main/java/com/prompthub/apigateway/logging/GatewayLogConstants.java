package com.prompthub.apigateway.logging;

public final class GatewayLogConstants {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";

    public static final String REQUEST_ID_ATTRIBUTE = "prompthub.gateway.requestId";
    public static final String AUTHENTICATED_ATTRIBUTE = "prompthub.gateway.authenticated";
    public static final String USER_ROLE_ATTRIBUTE = "prompthub.gateway.userRole";

    public static final String EVENT_TYPE = "GATEWAY_ACCESS";
    public static final String SERVICE_NAME = "apigateway";
    public static final String ACCESS_MESSAGE = "Gateway access";
    public static final String UNKNOWN = "unknown";

    private GatewayLogConstants() {
    }
}
