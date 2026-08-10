package com.prompthub.product.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SwaggerConfigTest {

	@Test
	@DisplayName("내부 인증 헤더는 숨기고 실제 API 파라미터는 유지한다")
	void hideInternalAuthHeaders_removesOnlyGatewayHeaders() {
		Operation operation = new Operation().parameters(new ArrayList<>(List.of(
			new Parameter().name("X-User-Id").in("header"),
			new Parameter().name("X-User-Role").in("header"),
			new Parameter().name("productId").in("path")
		)));
		OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem(
			"/api/v2/products/{productId}", new PathItem().get(operation)));

		new SwaggerConfig().hideInternalAuthHeaders().customise(openApi);

		assertThat(operation.getParameters())
			.extracting(Parameter::getName)
			.containsExactly("productId");
	}
}
