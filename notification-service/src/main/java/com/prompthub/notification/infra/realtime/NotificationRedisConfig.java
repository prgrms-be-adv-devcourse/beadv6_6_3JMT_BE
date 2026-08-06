package com.prompthub.notification.infra.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(prefix = "prompthub.notification.redis-pubsub", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRedisConfig {
    public static final String CHANNEL = "notification:events";

    @Bean
    RedisMessageListenerContainer notificationRedisListenerContainer(
        RedisConnectionFactory connectionFactory, RedisSubscriber subscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(CHANNEL));
        return container;
    }
}
