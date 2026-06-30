package com.prompthub.order.global.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

	private final OrderServiceAuthInterceptor orderServiceAuthInterceptor;
	private final AdminAuthInterceptor adminAuthInterceptor;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/v1/**")
			.allowedOrigins("http://localhost:3000")
			.allowedMethods(
				HttpMethod.GET.name(),
				HttpMethod.POST.name(),
				HttpMethod.PUT.name(),
				HttpMethod.PATCH.name(),
				HttpMethod.DELETE.name(),
				HttpMethod.OPTIONS.name()
			)
			.allowedHeaders(
				HttpHeaders.CONTENT_TYPE,
				HttpHeaders.AUTHORIZATION,
				AuthHeaders.USER_ID,
				AuthHeaders.USER_ROLE
			);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(orderServiceAuthInterceptor)
			.addPathPatterns(
				"/api/v1/orders",
				"/api/v1/orders/**",
				"/api/v1/cart",
				"/api/v1/cart/**"
			);
		registry.addInterceptor(adminAuthInterceptor)
			.addPathPatterns(
				"/api/v1/admin/orders",
				"/api/v1/admin/orders/**"
			);
	}
}
