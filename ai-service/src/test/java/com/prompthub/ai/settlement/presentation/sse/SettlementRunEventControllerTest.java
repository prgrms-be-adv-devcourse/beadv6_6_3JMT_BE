package com.prompthub.ai.settlement.presentation.sse;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.global.exception.GlobalExceptionHandler;
import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.domain.run.AgentRun;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettlementRunEventControllerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-22T12:00:30Z"),
            ZoneOffset.UTC
    );

    @Test
    void runningStreamUsesFirstClaimForDeltaAndSetsStreamingHeaders() throws Exception {
        UUID actorId = UUID.randomUUID();
        AgentRun run = AgentRun.start(
                UUID.randomUUID(),
                actorId,
                "정산 요약",
                Instant.parse("2026-07-22T12:00:00Z"),
                Duration.ofSeconds(90)
        );
        SettlementChatUseCase useCase = mock(SettlementChatUseCase.class);
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        when(useCase.getOwnedRun(actorId, run.runId())).thenReturn(run);
        when(useCase.claimFirstStream(actorId, run.runId())).thenReturn(true);
        SettlementRunEventController controller = new SettlementRunEventController(
                useCase,
                registry,
                com.prompthub.ai.settlement.AiSettlementTestFixtures.properties(true),
                CLOCK
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v2/ai/settlement/runs/{runId}/events", run.runId())
                        .header("X-User-Id", actorId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"));

        ArgumentCaptor<SseEmitter> emitter = ArgumentCaptor.forClass(SseEmitter.class);
        verify(registry).register(org.mockito.ArgumentMatchers.eq(run.runId()), emitter.capture(),
                org.mockito.ArgumentMatchers.eq(true));
        assertThat(emitter.getValue().getTimeout()).isEqualTo(90_000L);
        verify(registry).sendSnapshot(run.runId(), emitter.getValue(), run);
        emitter.getValue().complete();
    }

    @Test
    void reconnectDoesNotAcceptDeltasAndOwnershipMismatchIsNotFound() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AgentRun run = AgentRun.start(
                UUID.randomUUID(),
                actorId,
                "정산 요약",
                Instant.parse("2026-07-22T12:00:00Z"),
                Duration.ofSeconds(90)
        );
        SettlementChatUseCase useCase = mock(SettlementChatUseCase.class);
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        when(useCase.getOwnedRun(actorId, run.runId())).thenReturn(run);
        when(useCase.claimFirstStream(actorId, run.runId())).thenReturn(false);
        when(useCase.getOwnedRun(actorId, runId)).thenThrow(new AiException(AiErrorCode.AI_RUN_NOT_FOUND));
        SettlementRunEventController controller = new SettlementRunEventController(
                useCase,
                registry,
                com.prompthub.ai.settlement.AiSettlementTestFixtures.properties(true),
                CLOCK
        );

        SseEmitter emitter = controller.events(actorId, run.runId()).getBody();
        assertThat(emitter).isNotNull();
        verify(registry).register(run.runId(), emitter, false);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mockMvc.perform(get("/api/v2/ai/settlement/runs/{runId}/events", runId)
                        .header("X-User-Id", actorId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
        emitter.complete();
    }

    @Test
    void disabledSseStopsBeforeOwnershipLookup() throws Exception {
        SettlementChatUseCase useCase = mock(SettlementChatUseCase.class);
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        SettlementRunEventController controller = new SettlementRunEventController(
                useCase,
                registry,
                com.prompthub.ai.settlement.AiSettlementTestFixtures.properties(false),
                CLOCK
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v2/ai/settlement/runs/{runId}/events", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(useCase);
        verify(registry, never()).register(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
