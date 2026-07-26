package com.prompthub.ai.settlement.application.service.conversation;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SettlementConversationHistorySelector {

    private final TokenCountEstimator tokenCountEstimator;
    private final int maxTokens;

    @Autowired
    public SettlementConversationHistorySelector(
            TokenCountEstimator tokenCountEstimator,
            AiSettlementProperties properties
    ) {
        this(tokenCountEstimator, properties.historyMaxTokens());
    }

    SettlementConversationHistorySelector(TokenCountEstimator tokenCountEstimator, int maxTokens) {
        this.tokenCountEstimator = Objects.requireNonNull(tokenCountEstimator, "tokenCountEstimator");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens는 1 이상이어야 합니다.");
        }
        this.maxTokens = maxTokens;
    }

    public List<ChatPair> select(List<ChatPair> completedPairs) {
        Objects.requireNonNull(completedPairs, "completedPairs");
        List<ChatPair> selectedNewestFirst = new ArrayList<>();
        int selectedTokens = 0;
        for (int index = completedPairs.size() - 1; index >= 0; index--) {
            ChatPair pair = Objects.requireNonNull(completedPairs.get(index), "completedPair");
            int pairTokens = tokenCountEstimator.estimate(pair.user().content())
                    + tokenCountEstimator.estimate(pair.assistant().content());
            if (pairTokens > maxTokens - selectedTokens) {
                break;
            }
            selectedNewestFirst.add(pair);
            selectedTokens += pairTokens;
        }

        List<ChatPair> chronological = new ArrayList<>(selectedNewestFirst.size());
        for (int index = selectedNewestFirst.size() - 1; index >= 0; index--) {
            chronological.add(selectedNewestFirst.get(index));
        }
        return List.copyOf(chronological);
    }
}
