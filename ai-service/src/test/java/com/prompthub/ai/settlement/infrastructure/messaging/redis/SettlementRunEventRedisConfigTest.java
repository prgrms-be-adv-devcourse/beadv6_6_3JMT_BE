package com.prompthub.ai.settlement.infrastructure.messaging.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementRunEventRedisConfigTest {

    @Test
    void configuresSettlementRunEventPatternListener() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisSettlementRunEventSubscriber subscriber =
                mock(RedisSettlementRunEventSubscriber.class);
        SettlementRunEventRedisConfig config = new SettlementRunEventRedisConfig();

        RedisMessageListenerContainer container =
                config.aiSettlementRedisMessageListenerContainer(connectionFactory, subscriber);

        assertThat(container.getConnectionFactory()).isSameAs(connectionFactory);
        Map<MessageListener, Set<Topic>> listenerTopics = listenerTopics(container);
        assertThat(listenerTopics).containsKey(subscriber);
        assertThat(listenerTopics.get(subscriber))
                .containsExactly(new PatternTopic("ai:settlement:events:*"));
    }

    @SuppressWarnings("unchecked")
    private Map<MessageListener, Set<Topic>> listenerTopics(
            RedisMessageListenerContainer container
    ) {
        return (Map<MessageListener, Set<Topic>>)
                ReflectionTestUtils.getField(container, "listenerTopics");
    }
}
