package com.prompthub.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class AiServiceApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean(name = "redisConnectionFactory")
    private LettuceConnectionFactory redisConnectionFactory;

    @MockitoBean(name = "aiSettlementRedisMessageListenerContainer")
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @Test
    void startsWithoutPersistenceOrKafkaInfrastructure() {
        assertThat(applicationContext.containsBean("dataSource")).isFalse();
        assertThat(applicationContext.containsBean("entityManagerFactory")).isFalse();
        assertThat(applicationContext.containsBean("flyway")).isFalse();
        assertThat(applicationContext.getBeanDefinitionNames())
                .noneMatch(beanName -> beanName.toLowerCase().contains("kafka"));
    }
}
