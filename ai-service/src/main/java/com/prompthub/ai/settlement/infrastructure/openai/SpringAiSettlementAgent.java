package com.prompthub.ai.settlement.infrastructure.openai;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SettlementAgent;
import com.prompthub.ai.settlement.application.service.HistoryTokenSelector;
import com.prompthub.ai.settlement.domain.conversation.ChatPair;
import com.prompthub.ai.settlement.domain.run.RunStage;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpringAiSettlementAgent implements SettlementAgent {

    private static final int MAX_TOOL_ROUNDS = 4;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final HistoryTokenSelector historyTokenSelector;
    private final SettlementPromptFactory promptFactory;
    private final OpenAiCallRetryExecutor retryExecutor;
    private final FinalAnswerPolicy finalAnswerPolicy;
    private final ToolExecutionGuard executionGuard;
    private final AiSettlementProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final List<ToolCallback> toolCallbacks;

    public SpringAiSettlementAgent(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            SettlementAnalysisTools analysisTools,
            HistoryTokenSelector historyTokenSelector,
            SettlementPromptFactory promptFactory,
            OpenAiCallRetryExecutor retryExecutor,
            FinalAnswerPolicy finalAnswerPolicy,
            ToolExecutionGuard executionGuard,
            AiSettlementProperties properties,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.historyTokenSelector = historyTokenSelector;
        this.promptFactory = promptFactory;
        this.retryExecutor = retryExecutor;
        this.finalAnswerPolicy = finalAnswerPolicy;
        this.executionGuard = executionGuard;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.toolCallbacks = List.copyOf(Arrays.asList(ToolCallbacks.from(analysisTools)));
    }

    @Override
    public AgentResult answer(AgentRequest request) {
        Map<String, Object> serverToolContext = Map.of(
                ToolExecutionGuard.ACTOR_ID, request.actorId().toString(),
                ToolExecutionGuard.RUN_ID, request.runId().toString());
        ToolContext guardContext = new ToolContext(serverToolContext);
        executionGuard.assertRunning(guardContext);

        OpenAiChatOptions toolOptions = toolOptions(serverToolContext);
        Prompt prompt = new Prompt(initialMessages(request), toolOptions);
        ChatResponse response = callModel(request.runId(), request.deadlineAt(), prompt);
        int toolRounds = 0;

        while (response.hasToolCalls()) {
            if (toolRounds == MAX_TOOL_ROUNDS) {
                log.warn("AI settlement tool loop limit exceeded. runId={}, rounds={}",
                        request.runId(), toolRounds);
                throw new AiException(AiErrorCode.TOOL_LOOP_LIMIT_EXCEEDED);
            }
            executionGuard.assertRunning(guardContext);
            if (toolRounds == 0) {
                request.progressListener().onStage(RunStage.FETCHING_DATA);
            }
            ToolExecutionResult executionResult = executeTools(prompt, response, request.runId());
            toolRounds++;
            executionGuard.assertRunning(guardContext);
            prompt = new Prompt(executionResult.conversationHistory(), toolOptions);
            response = callModel(request.runId(), request.deadlineAt(), prompt);
        }

        List<Message> finalMessages = new ArrayList<>(prompt.getInstructions());
        finalMessages.add(requireAssistantMessage(response));
        finalMessages.add(new SystemMessage(promptFactory.finalAnswerPrompt()));
        executionGuard.assertRunning(guardContext);
        Prompt finalPrompt = new Prompt(finalMessages, finalOptions());
        request.progressListener().onStage(RunStage.GENERATING_ANSWER);
        List<ChatResponse> streamedResponses = retryExecutor.execute(
                request.runId(),
                request.deadlineAt(),
                () -> bufferFinalStream(finalPrompt, request.deadlineAt()));
        executionGuard.assertRunning(guardContext);
        recordStreamUsage(streamedResponses);

        String candidate = streamedResponses.stream()
                .map(this::responseText)
                .reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
                .toString();
        FinalAnswerPolicy.ValidatedAnswer validated = finalAnswerPolicy.validateAndChunk(candidate);
        log.info("AI settlement agent completed. runId={}, model={}, toolRounds={}",
                request.runId(), properties.model(), toolRounds);
        return new AgentResult(validated.answer(), validated.chunks(), toolRounds);
    }

    private List<Message> initialMessages(AgentRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptFactory.systemPrompt()));
        messages.add(new SystemMessage(promptFactory.currentDatePrompt()));
        for (ChatPair pair : historyTokenSelector.select(request.completedHistory())) {
            messages.add(new UserMessage(pair.user().content()));
            messages.add(new AssistantMessage(pair.assistant().content()));
        }
        messages.add(new UserMessage(request.question()));
        return messages;
    }

    private OpenAiChatOptions toolOptions(Map<String, Object> toolContext) {
        return OpenAiChatOptions.builder()
                .model(properties.model())
                .reasoningEffort(properties.reasoningEffort())
                .maxCompletionTokens(properties.maxCompletionTokens())
                .maxRetries(0)
                .store(false)
                .parallelToolCalls(true)
                .toolCallbacks(toolCallbacks)
                .toolContext(toolContext)
                .build();
    }

    private OpenAiChatOptions finalOptions() {
        return OpenAiChatOptions.builder()
                .model(properties.model())
                .reasoningEffort(properties.reasoningEffort())
                .maxCompletionTokens(properties.maxCompletionTokens())
                .maxRetries(0)
                .store(false)
                .parallelToolCalls(false)
                .streamUsage(true)
                .toolCallbacks(List.of())
                .toolContext(Map.of())
                .build();
    }

    private ChatResponse callModel(UUID runId, Instant deadlineAt, Prompt prompt) {
        ChatResponse response = retryExecutor.execute(
                runId,
                deadlineAt,
                () -> chatModel.call(prompt));
        requireAssistantMessage(response);
        recordUsage(response.getMetadata().getUsage());
        return response;
    }

    private ToolExecutionResult executeTools(Prompt prompt, ChatResponse response, UUID runId) {
        try {
            // DefaultToolCallingManager는 response의 tool call 목록을 입력 순서대로 동기 실행한다.
            return toolCallingManager.executeToolCalls(prompt, response);
        } catch (RuntimeException exception) {
            AiException aiException = findAiException(exception);
            if (aiException != null) {
                throw aiException;
            }
            log.warn("AI settlement tool execution failed. runId={}, errorCode={}",
                    runId, AiErrorCode.AI_PROVIDER_UNAVAILABLE.getCode());
            throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private AiException findAiException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AiException aiException) {
                return aiException;
            }
            current = current.getCause();
        }
        return null;
    }

    private List<ChatResponse> bufferFinalStream(Prompt finalPrompt, Instant deadlineAt) {
        Duration remaining = Duration.between(clock.instant(), deadlineAt);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new AiException(AiErrorCode.RUN_TIMEOUT);
        }
        List<ChatResponse> responses = chatModel.stream(finalPrompt)
                .timeout(remaining)
                .collectList()
                .block();
        if (responses == null || responses.isEmpty()) {
            throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        return List.copyOf(responses);
    }

    private AssistantMessage requireAssistantMessage(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        return response.getResult().getOutput();
    }

    private String responseText(ChatResponse response) {
        AssistantMessage message = requireAssistantMessage(response);
        return message.getText() == null ? "" : message.getText();
    }

    private void recordStreamUsage(List<ChatResponse> responses) {
        for (int index = responses.size() - 1; index >= 0; index--) {
            Usage usage = responses.get(index).getMetadata().getUsage();
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                recordUsage(usage);
                return;
            }
        }
    }

    private void recordUsage(Usage usage) {
        if (usage == null) {
            return;
        }
        incrementTokens("input", usage.getPromptTokens());
        incrementTokens("output", usage.getCompletionTokens());
        Long cachedInput = usage.getCacheReadInputTokens();
        if (cachedInput != null && cachedInput > 0) {
            meterRegistry.counter(
                    "ai.openai.tokens",
                    "model", properties.model(),
                    "type", "cached_input").increment(cachedInput);
        }
    }

    private void incrementTokens(String type, Integer tokens) {
        if (tokens != null && tokens > 0) {
            meterRegistry.counter(
                    "ai.openai.tokens",
                    "model", properties.model(),
                    "type", type).increment(tokens);
        }
    }
}
