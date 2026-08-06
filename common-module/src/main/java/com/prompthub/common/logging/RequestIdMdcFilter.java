package com.prompthub.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestIdMdcFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = validUuidOrNew(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String validUuidOrNew(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException ignored) {
                // Invalid external values must not become log correlation identifiers.
            }
        }
        return UUID.randomUUID().toString();
    }
}
