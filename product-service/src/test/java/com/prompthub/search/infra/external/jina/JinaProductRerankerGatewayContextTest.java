package com.prompthub.search.infra.external.jina;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 생성자가 2개인데 어느 쪽에도 @Autowired가 없으면, Spring이 자동 주입 대상을 하나로
 * 좁히지 못하고 기본 생성자를 찾다 실패한다(#740, NoSuchMethodException). 단위 테스트
 * (JinaProductRerankerGatewayTest)는 생성자를 직접 new로 호출해 이 문제를 잡지 못했다 —
 * 여기서는 실제 Spring 컨테이너가 이 빈을 정상 생성하는지 확인한다. ElasticsearchClientConfig
 * 전체를 띄우면 ES 컨테이너가 필요해지므로, 필요한 설정만 최소로 올린다.
 */
@SpringBootTest(classes = {
	JinaProductRerankerGateway.class, JinaProductRerankerGatewayContextTest.ObjectMapperTestConfig.class})
@EnableConfigurationProperties(JinaRerankerProperties.class)
class JinaProductRerankerGatewayContextTest {

	@Autowired
	private JinaProductRerankerGateway gateway;

	@Test
	void 스프링_컨테이너가_생성자_주입으로_빈을_생성한다() {
		assertThat(gateway).isNotNull();
	}

	@Configuration
	static class ObjectMapperTestConfig {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
