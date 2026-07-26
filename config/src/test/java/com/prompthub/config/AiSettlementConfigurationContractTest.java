package com.prompthub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class AiSettlementConfigurationContractTest {

    @Test
    void ai_정산_운영_설정과_User_내부_계약을_제공한다() {
        Properties global = load("configs/application.yml");
        Properties ai = load("configs/ai-service.yml");
        Properties user = load("configs/user-service.yml");

        assertThat(global.getProperty("gateway.api-versions.ai-service[0]")).isEqualTo("v2");
        assertThat(global.getProperty("gateway.route-policies./api/*/ai/settlement/**"))
            .isEqualTo("SELLER_OR_ADMIN");

        assertThat(ai.getProperty("server.port")).isEqualTo("18087");
        assertThat(ai.getProperty("spring.data.redis.database")).isEqualTo("1");
        assertThat(ai.getProperty("spring.grpc.client.channel.user-service.target"))
            .isEqualTo("static://user-service:${USER_GRPC_SERVER_PORT}");
        assertThat(ai.getProperty("ai.model")).isEqualTo("${OPENAI_MODEL:gpt-5.6-luna}");
        assertThat(ai.getProperty("ai.settlement.chat.enabled"))
            .isEqualTo("${AI_SETTLEMENT_CHAT_ENABLED:false}");
        assertThat(ai.getProperty("spring.ai.openai.api-key")).isEqualTo("${OPENAI_API_KEY}");

        assertThat(user.getProperty("user.kafka.listener.settlement.enabled"))
            .isEqualTo("${USER_SETTLEMENT_KAFKA_LISTENER_ENABLED:false}");
        assertThat(user.getProperty("user.grpc.seller-settlement.internal-token"))
            .isEqualTo("${AI_USER_GRPC_TOKEN}");
    }

    private Properties load(String path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(path));
        return factory.getObject();
    }
}
