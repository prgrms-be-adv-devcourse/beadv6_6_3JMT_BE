package com.prompthub.ai.global.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerLoggingTest {

    private static final String SENSITIVE_CAUSE =
            "ai:settlement:actor:{78ad0885-bfa8-4a64-a39e-f71657668192}:active-run";

    @Test
    void serverErrorsLogOnlySafeCodeAndExceptionCategory(CapturedOutput output) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        handler.handleBusinessException(new AiException(
                AiErrorCode.AI_STATE_UNAVAILABLE,
                new IllegalStateException(SENSITIVE_CAUSE)
        ));
        handler.handleException(new IllegalArgumentException(SENSITIVE_CAUSE));

        assertThat(output)
                .contains("AI_STATE_UNAVAILABLE")
                .contains("IllegalStateException")
                .contains("IllegalArgumentException")
                .doesNotContain(SENSITIVE_CAUSE);
    }
}
