package com.prompthub.order.presentation;

import com.prompthub.order.global.web.AdminAuthInterceptor;
import com.prompthub.order.global.web.OrderServiceAuthInterceptor;
import com.prompthub.order.global.web.WebMvcConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCorsTest {

	@Test
	@DisplayName("주문 API CORS 설정은 로컬 프론트와 gateway 인증 헤더를 허용한다")
	void orderCors_allowsLocalFrontendAndGatewayHeaders() {
		// given
		WebMvcConfig webMvcConfig = new WebMvcConfig(
			new OrderServiceAuthInterceptor(),
			new AdminAuthInterceptor()
		);
		TestCorsRegistry registry = new TestCorsRegistry();

		// when
		webMvcConfig.addCorsMappings(registry);

		// then
		CorsConfiguration config = registry.configurations().get("/api/v1/**");
		assertThat(config).isNotNull();
		assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:3000");
		assertThat(config.getAllowedMethods()).containsExactly(
			HttpMethod.GET.name(),
			HttpMethod.POST.name(),
			HttpMethod.PUT.name(),
			HttpMethod.PATCH.name(),
			HttpMethod.DELETE.name(),
			HttpMethod.OPTIONS.name()
		);
		assertThat(config.getAllowedHeaders()).containsExactly(
			HttpHeaders.CONTENT_TYPE,
			HttpHeaders.AUTHORIZATION,
			"X-User-Id",
			"X-User-Role"
		);
	}

	@Test
	@DisplayName("사용자 인증 인터셉터는 CORS preflight OPTIONS 요청을 인증 없이 통과시킨다")
	void orderAuthInterceptor_optionsRequest_passesWithoutAuthHeaders() {
		// given
		OrderServiceAuthInterceptor interceptor = new OrderServiceAuthInterceptor();
		MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.OPTIONS.name(), "/api/v1/orders");
		request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name());
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		boolean result = interceptor.preHandle(request, response, new Object());

		// then
		assertThat(result).isTrue();
	}

	private static class TestCorsRegistry extends CorsRegistry {

		Map<String, CorsConfiguration> configurations() {
			return getCorsConfigurations();
		}
	}
}
