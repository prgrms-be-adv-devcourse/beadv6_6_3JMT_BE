package com.prompthub.ai.settlement.presentation;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.GlobalExceptionHandler;
import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.domain.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettlementChatControllerTest {

    private static final String BASE_PATH = "/api/v2/ai/settlement/conversations/current";

    @Test
    void returnsEmptyConversationAndAcceptsQuestionWithoutRoleHeader() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        SettlementChatUseCase useCase = mock(SettlementChatUseCase.class);
        when(useCase.getCurrentConversation(actorId)).thenReturn(Optional.empty());
        when(useCase.acceptQuestion(actorId, "지난달 정산 요약")).thenReturn(
                new SettlementChatUseCase.AcceptedRun(
                        conversationId,
                        runId,
                        RunStatus.RUNNING,
                        Instant.parse("2026-07-22T12:00:00Z"),
                        Instant.parse("2026-07-22T12:01:30Z")
                ));
        AiSettlementProperties properties = properties(true);
        MockMvc mockMvc = mockMvc(new SettlementChatController(useCase, properties), properties);

        mockMvc.perform(get(BASE_PATH).header("X-User-Id", actorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("success"));

        mockMvc.perform(post(BASE_PATH + "/messages")
                        .header("X-User-Id", actorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"지난달 정산 요약\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.data.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.message").value("accepted"));
    }

    @Test
    void trimsBeforeApplyingTwoThousandCharacterLimitAndCallingUseCase() throws Exception {
        UUID actorId = UUID.randomUUID();
        String trimmedContent = "가".repeat(2_000);
        SettlementChatUseCase useCase = mock(SettlementChatUseCase.class);
        when(useCase.acceptQuestion(actorId, trimmedContent)).thenReturn(
                new SettlementChatUseCase.AcceptedRun(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        RunStatus.RUNNING,
                        Instant.parse("2026-07-22T12:00:00Z"),
                        Instant.parse("2026-07-22T12:01:30Z")
                ));
        AiSettlementProperties properties = properties(true);
        MockMvc mockMvc = mockMvc(new SettlementChatController(useCase, properties), properties);

        mockMvc.perform(post(BASE_PATH + "/messages")
                        .header("X-User-Id", actorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + trimmedContent + "   \"}"))
                .andExpect(status().isAccepted());

        verify(useCase).acceptQuestion(actorId, trimmedContent);
    }

    @Test
    void beanValidationRejectsBlankQuestion() throws Exception {
        SettlementChatUseCase useCase = mock(SettlementChatUseCase.class);
        AiSettlementProperties properties = properties(true);
        MockMvc mockMvc = mockMvc(new SettlementChatController(useCase, properties), properties);

        mockMvc.perform(post(BASE_PATH + "/messages")
                        .header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(AiErrorCode.INVALID_CHAT_MESSAGE.getCode()));

        verifyNoInteractions(useCase);
    }

    @Test
    void disabledControllerStopsBeforeUseCase() throws Exception {
        SettlementChatUseCase useCase = mock(SettlementChatUseCase.class);
        AiSettlementProperties properties = properties(false);
        MockMvc mockMvc = mockMvc(new SettlementChatController(useCase, properties), properties);

        mockMvc.perform(post(BASE_PATH + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(AiErrorCode.AI_CHAT_DISABLED.getCode()));

        verifyNoInteractions(useCase);
    }

    private static MockMvc mockMvc(
            SettlementChatController controller,
            AiSettlementProperties properties
    ) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addInterceptors(new AiSettlementFeatureInterceptor(
                        properties,
                        tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                ))
                .build();
    }

    static AiSettlementProperties properties(boolean enabled) {
        return new AiSettlementProperties(
                "gpt-5.6-luna",
                "low",
                2_000,
                8_000,
                Duration.ofSeconds(90),
                Duration.ofSeconds(3),
                new AiSettlementProperties.Execution(4),
                new AiSettlementProperties.Conversation(Duration.ofHours(24), 20),
                new AiSettlementProperties.Sse(Duration.ofSeconds(15)),
                new AiSettlementProperties.Settlement(new AiSettlementProperties.Chat(enabled)),
                "internal-token"
        );
    }
}
