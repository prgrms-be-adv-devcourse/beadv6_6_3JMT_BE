package com.prompthub.ai.settlement.presentation.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSettlementChatMessageRequest(
        @NotBlank
        @Size(max = 2_000)
        String content
) {

    public CreateSettlementChatMessageRequest {
        if (content != null) {
            content = content.strip();
        }
    }
}
