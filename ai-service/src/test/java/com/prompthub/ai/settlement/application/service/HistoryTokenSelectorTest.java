package com.prompthub.ai.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.prompthub.ai.settlement.domain.conversation.ChatMessage;
import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.TokenCountEstimator;

@DisplayName("AI 정산 대화 이력 token 선택")
class HistoryTokenSelectorTest {

    private final TokenCountEstimator estimator = mock(TokenCountEstimator.class);

    @Test
    @DisplayName("최근 완결 pair부터 예산 안에서 선택하고 pair 일부만 자르지 않는다")
    void selectsNewestWholePairsWithinBudget() {
        HistoryTokenSelector selector = new HistoryTokenSelector(estimator, 10);
        ChatPair older = pair("old-user", "old-assistant", Instant.parse("2026-07-20T00:00:00Z"));
        ChatPair newest = pair("new-user", "new-assistant", Instant.parse("2026-07-21T00:00:00Z"));
        given(estimator.estimate("old-user")).willReturn(3);
        given(estimator.estimate("old-assistant")).willReturn(3);
        given(estimator.estimate("new-user")).willReturn(4);
        given(estimator.estimate("new-assistant")).willReturn(4);

        assertThat(selector.select(List.of(older, newest)))
                .containsExactly(newest);
    }

    @Test
    @DisplayName("가장 최근 pair가 예산보다 크면 이전 pair를 대신 노출하지 않는다")
    void returnsEmptyWhenNewestPairAloneExceedsBudget() {
        HistoryTokenSelector selector = new HistoryTokenSelector(estimator, 10);
        ChatPair older = pair("old-user", "old-assistant", Instant.parse("2026-07-20T00:00:00Z"));
        ChatPair newest = pair("new-user", "new-assistant", Instant.parse("2026-07-21T00:00:00Z"));
        given(estimator.estimate("new-user")).willReturn(6);
        given(estimator.estimate("new-assistant")).willReturn(6);

        assertThat(selector.select(List.of(older, newest))).isEmpty();
    }

    private ChatPair pair(String user, String assistant, Instant createdAt) {
        return new ChatPair(
                ChatMessage.user(user, createdAt),
                ChatMessage.assistant(assistant, createdAt.plusSeconds(1)));
    }
}
