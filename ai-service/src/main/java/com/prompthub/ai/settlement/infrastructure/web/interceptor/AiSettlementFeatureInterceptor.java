package com.prompthub.ai.settlement.infrastructure.web.interceptor;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.exception.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AiSettlementFeatureInterceptor implements HandlerInterceptor {

    private final AiSettlementProperties properties;
    private final ObjectMapper objectMapper;

    public AiSettlementFeatureInterceptor(
            AiSettlementProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {
        if (properties.settlement().chat().enabled()) {
            return true;
        }

        response.setStatus(AiErrorCode.AI_CHAT_DISABLED.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(AiErrorCode.AI_CHAT_DISABLED)
        );
        return false;
    }
}
