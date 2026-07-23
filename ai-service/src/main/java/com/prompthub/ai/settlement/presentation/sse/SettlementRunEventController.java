package com.prompthub.ai.settlement.presentation.sse;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.domain.AgentRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/ai/settlement")
public class SettlementRunEventController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final Duration SSE_TIMEOUT_MARGIN = Duration.ofSeconds(30);

    private final SettlementChatUseCase useCase;
    private final SseEmitterRegistry emitterRegistry;
    private final AiSettlementProperties properties;
    private final Clock clock;

    public SettlementRunEventController(
            SettlementChatUseCase useCase,
            SseEmitterRegistry emitterRegistry,
            AiSettlementProperties properties,
            Clock clock
    ) {
        this.useCase = useCase;
        this.emitterRegistry = emitterRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    @Operation(summary = "셀러 정산 AI run SSE 구독")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 연결 성공"),
            @ApiResponse(responseCode = "404", description = "run이 없거나 소유자가 다름"),
            @ApiResponse(responseCode = "503", description = "AI 채팅 비활성화 또는 상태 저장소 장애")
    })
    @GetMapping("/runs/{runId}/events")
    public ResponseEntity<SseEmitter> events(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @PathVariable UUID runId
    ) {
        assertEnabled();
        AgentRun initial = useCase.getOwnedRun(actorId, runId);
        SseEmitter emitter = new SseEmitter(emitterTimeout(initial));
        if (initial.status().isTerminal()) {
            emitterRegistry.sendTerminal(runId, emitter, initial);
            return response(emitter);
        }

        boolean acceptDeltas = useCase.claimFirstStream(actorId, runId);
        emitterRegistry.register(runId, emitter, acceptDeltas);
        AgentRun latest = useCase.getOwnedRun(actorId, runId);
        if (latest.status().isTerminal()) {
            emitterRegistry.sendTerminal(runId, emitter, latest);
        } else {
            emitterRegistry.sendSnapshot(runId, emitter, latest);
        }
        return response(emitter);
    }

    private ResponseEntity<SseEmitter> response(SseEmitter emitter) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private long emitterTimeout(AgentRun run) {
        Duration remaining = Duration.between(clock.instant(), run.deadlineAt());
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        return remaining.plus(SSE_TIMEOUT_MARGIN).toMillis();
    }

    private void assertEnabled() {
        if (!properties.settlement().chat().enabled()) {
            throw new AiException(AiErrorCode.AI_CHAT_DISABLED);
        }
    }
}
