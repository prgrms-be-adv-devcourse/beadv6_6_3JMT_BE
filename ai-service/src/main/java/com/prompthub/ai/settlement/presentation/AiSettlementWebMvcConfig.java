package com.prompthub.ai.settlement.presentation;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AiSettlementWebMvcConfig implements WebMvcConfigurer {

    private final AiSettlementFeatureInterceptor featureInterceptor;

    public AiSettlementWebMvcConfig(AiSettlementFeatureInterceptor featureInterceptor) {
        this.featureInterceptor = featureInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(featureInterceptor)
                .addPathPatterns("/api/v2/ai/settlement/**");
    }
}
