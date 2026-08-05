package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class SettlementRunEventRedisConfig {

    @Bean
    public RedisMessageListenerContainer aiSettlementRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSettlementRunEventSubscriber subscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new PatternTopic("ai:settlement:events:*"));
        return container;
    }
}
