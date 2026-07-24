package com.prompthub.ai.global.config;

import com.prompthub.ai.settlement.infrastructure.messaging.redis.RedisSettlementRunEventSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class AiRedisConfig {

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

    @Bean("acceptRunScript")
    public DefaultRedisScript<List> acceptRunScript() {
        return script("redis/accept-run.lua", List.class);
    }

    @Bean("completeRunScript")
    public DefaultRedisScript<Long> completeRunScript() {
        return script("redis/complete-run.lua", Long.class);
    }

    @Bean("failRunScript")
    public DefaultRedisScript<Long> failRunScript() {
        return script("redis/fail-run.lua", Long.class);
    }

    @Bean("markRunCancelledScript")
    public DefaultRedisScript<List> markRunCancelledScript() {
        return script("redis/mark-run-cancelled.lua", List.class);
    }

    @Bean("cleanupCancelledConversationScript")
    public DefaultRedisScript<Long> cleanupCancelledConversationScript() {
        return script("redis/cleanup-cancelled-conversation.lua", Long.class);
    }

    @Bean("expireStaleRunScript")
    public DefaultRedisScript<Long> expireStaleRunScript() {
        return script("redis/expire-stale-run.lua", Long.class);
    }

    @Bean("updateStageScript")
    public DefaultRedisScript<Long> updateStageScript() {
        return script("redis/update-stage.lua", Long.class);
    }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
