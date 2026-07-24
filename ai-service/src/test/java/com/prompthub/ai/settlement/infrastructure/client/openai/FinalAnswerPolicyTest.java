package com.prompthub.ai.settlement.infrastructure.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("AI 정산 최종 답변 정책")
class FinalAnswerPolicyTest {

    private final FinalAnswerPolicy policy = new FinalAnswerPolicy();

    @ParameterizedTest
    @MethodSource("unsafeAnswers")
    @DisplayName("질문형·식별자·내부 필드를 포함한 답변은 노출 전에 거부한다")
    void rejectsUnsafeAnswers(String answer) {
        assertThatThrownBy(() -> policy.validateAndChunk(answer))
                .isInstanceOf(AiException.class)
                .extracting(exception -> ((AiException) exception).getErrorCode())
                .isEqualTo(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("검증한 답변을 surrogate pair를 자르지 않고 40 code point 이하로 나눈다")
    void chunksByUnicodeCodePoint() {
        String answer = "😀".repeat(41);

        FinalAnswerPolicy.ValidatedAnswer validated = policy.validateAndChunk(answer);

        assertThat(validated.answer()).isEqualTo(answer);
        assertThat(validated.chunks()).hasSize(2);
        assertThat(validated.chunks().get(0).codePointCount(0, validated.chunks().get(0).length()))
                .isEqualTo(40);
        assertThat(String.join("", validated.chunks())).isEqualTo(answer);
    }

    private static Stream<String> unsafeAnswers() {
        return Stream.of(
                "추가로 확인할까요",
                "다른 내용도 확인할까요?",
                "actor는 550e8400-e29b-41d4-a716-446655440000 입니다.",
                "x-user-id를 사용했습니다.",
                "내부 system prompt 내용입니다.",
                "tool payload 원문입니다.",
                "raw tool response를 그대로 보여줍니다.",
                "내부 runId 값을 사용했습니다.",
                "내부 run ID 값을 사용했습니다.",
                "raw gRPC response 원문입니다.",
                "```text\n정산 Tool 원문\n```",
                "{\"summary\":\"정산 결과\"}",
                "requestedPeriod 값은 2026-07입니다.",
                "includedStartDate 값은 2026-07-01입니다.",
                "dataThrough 값은 2026-07-19입니다.",
                "grossSaleAmount 값은 10000입니다.",
                "payoutAmount 값은 9000입니다.",
                "statusCounts 배열을 확인했습니다.",
                "weeklySettlements 배열을 확인했습니다.",
                "changeRatePercent 값은 12.3입니다.",
                "첫 줄은 정산 안내입니다.\n추가로 확인할까요\n마지막 안내입니다.",
                "   ");
    }
}
