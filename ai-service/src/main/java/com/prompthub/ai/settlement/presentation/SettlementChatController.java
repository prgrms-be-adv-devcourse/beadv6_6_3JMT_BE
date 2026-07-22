package com.prompthub.ai.settlement.presentation;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.usecase.SettlementChatUseCase;
import com.prompthub.ai.settlement.presentation.dto.AcceptedRunResponse;
import com.prompthub.ai.settlement.presentation.dto.AiApiResponse;
import com.prompthub.ai.settlement.presentation.dto.ConversationResponse;
import com.prompthub.ai.settlement.presentation.dto.CreateSettlementChatMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/ai/settlement")
public class SettlementChatController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final SettlementChatUseCase useCase;
    private final AiSettlementProperties properties;

    public SettlementChatController(
            SettlementChatUseCase useCase,
            AiSettlementProperties properties
    ) {
        this.useCase = useCase;
        this.properties = properties;
    }

    @Operation(summary = "현재 셀러 정산 AI 대화 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "503", description = "AI 채팅 비활성화 또는 상태 저장소 장애")
    })
    @GetMapping("/conversations/current")
    public ResponseEntity<AiApiResponse<ConversationResponse>> getCurrentConversation(
            @RequestHeader(USER_ID_HEADER) UUID actorId
    ) {
        assertEnabled();
        ConversationResponse response = useCase.getCurrentConversation(actorId)
                .map(ConversationResponse::from)
                .orElse(null);
        return ResponseEntity.ok(AiApiResponse.success(response));
    }

    @Operation(summary = "셀러 정산 AI 질문 등록")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "질문 수락"),
            @ApiResponse(responseCode = "400", description = "질문 형식 오류"),
            @ApiResponse(responseCode = "409", description = "기존 run 실행 중"),
            @ApiResponse(responseCode = "429", description = "Pod 실행 용량 초과"),
            @ApiResponse(responseCode = "503", description = "AI 채팅 비활성화 또는 상태 저장소 장애")
    })
    @PostMapping("/conversations/current/messages")
    public ResponseEntity<AiApiResponse<AcceptedRunResponse>> acceptQuestion(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @Valid @RequestBody CreateSettlementChatMessageRequest request
    ) {
        assertEnabled();
        AcceptedRunResponse response = AcceptedRunResponse.from(
                useCase.acceptQuestion(actorId, request.content()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AiApiResponse.accepted(response));
    }

    @Operation(summary = "현재 셀러 정산 AI 대화 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 완료"),
            @ApiResponse(responseCode = "503", description = "AI 채팅 비활성화 또는 상태 저장소 장애")
    })
    @DeleteMapping("/conversations/current")
    public ResponseEntity<Void> deleteCurrentConversation(
            @RequestHeader(USER_ID_HEADER) UUID actorId
    ) {
        assertEnabled();
        useCase.deleteCurrentConversation(actorId);
        return ResponseEntity.noContent().build();
    }

    private void assertEnabled() {
        if (!properties.settlement().chat().enabled()) {
            throw new AiException(AiErrorCode.AI_CHAT_DISABLED);
        }
    }
}
