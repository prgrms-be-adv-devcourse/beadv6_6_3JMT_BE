package com.prompthub.ai.global.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AiErrorCodeTest {

    @Test
    void exposesStableHttpErrorCodes() {
        assertThat(AiErrorCode.INVALID_CHAT_MESSAGE.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(AiErrorCode.AI_RUN_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(AiErrorCode.RUN_IN_PROGRESS.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(AiErrorCode.AI_CAPACITY_EXCEEDED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(AiErrorCode.AI_CHAT_DISABLED.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(AiErrorCode.AI_STATE_UNAVAILABLE.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(AiErrorCode.values())
                .allSatisfy(errorCode -> assertThat(errorCode.getCode()).isEqualTo(errorCode.name()));
    }
}
