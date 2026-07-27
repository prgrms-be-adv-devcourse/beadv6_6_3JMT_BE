package com.prompthub.common.logging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdMdcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "requestIdMdcFilterRegistration")
    FilterRegistrationBean<RequestIdMdcFilter> requestIdMdcFilterRegistration() {
        FilterRegistrationBean<RequestIdMdcFilter> registration = new FilterRegistrationBean<>(new RequestIdMdcFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
