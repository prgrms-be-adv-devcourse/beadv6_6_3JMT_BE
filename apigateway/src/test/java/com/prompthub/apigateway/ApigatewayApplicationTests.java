package com.prompthub.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(properties = {
	"spring.cloud.config.enabled=false",
	"spring.cloud.config.fail-fast=false",
	"eureka.client.enabled=false"
})
class ApigatewayApplicationTests {

	@MockitoBean
	ReactiveJwtDecoder reactiveJwtDecoder;

	@Test
	void contextLoads() {
	}

}
