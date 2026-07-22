package com.prompthub.ai.settlement.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SettlementAgent;
import com.prompthub.ai.settlement.application.service.HistoryTokenSelector;
import com.prompthub.ai.settlement.domain.RunStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

@DisplayName("Spring AI 판매자 정산 수동 agent loop")
class SpringAiSettlementAgentTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private final ExecutorService providerCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        providerCallExecutor.shutdownNow();
    }

    @Test
    @DisplayName("한 Tool round의 history 뒤 별도 final prompt를 context 없이 stream하고 검증된 chunk만 반환한다")
    void buffersAndReturnsValidatedFinalAnswerWithoutIdentityInPrompt() {
        ChatModel model = mock(ChatModel.class);
        ToolCallingManager manager = mock(ToolCallingManager.class);
        HistoryTokenSelector historySelector = mock(HistoryTokenSelector.class);
        given(historySelector.select(List.of())).willReturn(List.of());
        given(model.call(any(Prompt.class)))
                .willReturn(toolResponse(), textResponse("최종 응답 준비"));
        given(manager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .willAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0, Prompt.class);
                    ChatResponse response = invocation.getArgument(1, ChatResponse.class);
                    List<Message> history = new ArrayList<>(prompt.getInstructions());
                    history.add(response.getResult().getOutput());
                    history.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    "tool-call-1",
                                    "get_settlement_summary",
                                    "{\"requestedPeriod\":\"2026-07\"}")))
                            .build());
                    return ToolExecutionResult.builder().conversationHistory(history).build();
                });
        given(model.stream(any(Prompt.class))).willReturn(Flux.just(
                textResponse("7월 정산은 "),
                textResponse("부분 집계입니다.")));
        AiSettlementProperties properties = properties();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiSettlementAgent agent = new SpringAiSettlementAgent(
                model,
                manager,
                mock(SettlementAnalysisTools.class),
                historySelector,
                new SettlementPromptFactory(CLOCK),
                new OpenAiCallRetryExecutor(
                        properties.model(), meterRegistry, CLOCK, duration -> { }, providerCallExecutor),
                new FinalAnswerPolicy(),
                mock(ToolExecutionGuard.class),
                properties,
                meterRegistry,
                CLOCK);
        UUID actorId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<RunStage> progress = new ArrayList<>();

        SettlementAgent.AgentResult result = agent.answer(new SettlementAgent.AgentRequest(
                actorId,
                runId,
                "7월 정산을 요약해줘",
                List.of(),
                NOW.plusSeconds(90),
                progress::add));

        assertThat(result.answer()).isEqualTo("7월 정산은 부분 집계입니다.");
        assertThat(String.join("", result.chunks())).isEqualTo(result.answer());
        assertThat(result.toolRounds()).isEqualTo(1);
        assertThat(progress).containsExactly(
                RunStage.FETCHING_DATA,
                RunStage.GENERATING_ANSWER);

        ArgumentCaptor<Prompt> callPrompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(callPrompt.capture());
        Prompt initialPrompt = callPrompt.getAllValues().getFirst();
        Prompt postToolPrompt = callPrompt.getAllValues().get(1);
        ToolCallingChatOptions initialOptions = (ToolCallingChatOptions) initialPrompt.getOptions();
        assertThat(initialOptions.getToolCallbacks()).hasSize(4);
        assertThat(initialOptions.getToolContext())
                .containsEntry("actorId", actorId.toString())
                .containsEntry("runId", runId.toString());
        assertThat(initialPrompt.getContents())
                .doesNotContain(actorId.toString(), runId.toString(), properties.userGrpcInternalToken());
        assertThat(postToolPrompt.getInstructions())
                .anyMatch(ToolResponseMessage.class::isInstance);
        verify(manager).executeToolCalls(any(Prompt.class), any(ChatResponse.class));

        ArgumentCaptor<Prompt> finalPrompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(finalPrompt.capture());
        ToolCallingChatOptions finalOptions = (ToolCallingChatOptions) finalPrompt.getValue().getOptions();
        assertThat(finalOptions.getToolCallbacks()).isEmpty();
        assertThat(finalOptions.getToolContext()).isEmpty();
    }

    @Test
    @DisplayName("네 번의 성공 Tool round 뒤 다시 Tool을 요구하면 다섯 번째를 실행하지 않는다")
    void stopsBeforeFifthToolRound() {
        ChatModel model = mock(ChatModel.class);
        ToolCallingManager manager = mock(ToolCallingManager.class);
        HistoryTokenSelector historySelector = mock(HistoryTokenSelector.class);
        given(historySelector.select(List.of())).willReturn(List.of());
        given(model.call(any(Prompt.class))).willReturn(toolResponse());
        given(manager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .willAnswer(invocation -> ToolExecutionResult.builder()
                        .conversationHistory(invocation.getArgument(0, Prompt.class).getInstructions())
                        .build());
        AiSettlementProperties properties = properties();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiSettlementAgent agent = new SpringAiSettlementAgent(
                model,
                manager,
                mock(SettlementAnalysisTools.class),
                historySelector,
                new SettlementPromptFactory(CLOCK),
                new OpenAiCallRetryExecutor(
                        properties.model(), meterRegistry, CLOCK, duration -> { }, providerCallExecutor),
                new FinalAnswerPolicy(),
                mock(ToolExecutionGuard.class),
                properties,
                meterRegistry,
                CLOCK);

        assertThatThrownBy(() -> agent.answer(new SettlementAgent.AgentRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "정산을 계속 조회해줘",
                List.of(),
                NOW.plusSeconds(90),
                stage -> { })))
                .isInstanceOf(AiException.class)
                .extracting(exception -> ((AiException) exception).getErrorCode())
                .isEqualTo(AiErrorCode.TOOL_LOOP_LIMIT_EXCEEDED);
        verify(manager, times(4)).executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        verify(model, times(5)).call(any(Prompt.class));
        verify(model, times(0)).stream(any(Prompt.class));
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private ChatResponse toolResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "tool-call-1",
                        "function",
                        "get_settlement_summary",
                        "{\"periodType\":\"MONTH\",\"period\":\"2026-07\"}")))
                .build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(message)))
                .build();
    }

    private AiSettlementProperties properties() {
        return new AiSettlementProperties(
                "gpt-5.6-luna",
                "low",
                2000,
                8000,
                Duration.ofSeconds(90),
                Duration.ofSeconds(3),
                new AiSettlementProperties.Execution(4),
                new AiSettlementProperties.Conversation(Duration.ofHours(24), 20),
                new AiSettlementProperties.Sse(Duration.ofSeconds(15)),
                new AiSettlementProperties.Settlement(new AiSettlementProperties.Chat(true)),
                "secret-internal-token");
    }
}
