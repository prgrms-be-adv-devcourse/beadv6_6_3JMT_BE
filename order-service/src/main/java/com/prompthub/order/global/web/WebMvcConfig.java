package com.prompthub.order.global.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

	private final OrderServiceAuthInterceptor orderServiceAuthInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(orderServiceAuthInterceptor)
			.addPathPatterns(
				"/api/v1/orders",
				"/api/v1/orders/**",
				"/api/v1/cart",
				"/api/v1/cart/**"
			);
	}
}
