package com.prompthub.ai.settlement.infrastructure.client.openai;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FinalAnswerPolicy {

    private static final int MAX_CHUNK_CODE_POINTS = 40;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])");
    private static final Pattern KOREAN_QUESTION_ENDING = Pattern.compile(
            "(?:나요|까요|습니까|인가요|하시겠어요|주시겠어요)\\s*[.!]?\\s*(?:\\R|$)");
    private static final Pattern INTERNAL_IDENTIFIER_PATTERN = Pattern.compile(
            "(?i)\\b(?:actor|seller|run|conversation|settlement(?:[\\s_-]*detail)?|order[\\s_-]*product)"
                    + "[\\s_-]*id\\b");
    private static final List<String> FORBIDDEN_INTERNAL_TERMS = List.of(
            "x-user-id",
            "x-internal-service-token",
            "system prompt",
            "tool payload",
            "raw tool",
            "raw grpc",
            "raw payload",
            "tool request",
            "tool response",
            "grpc request",
            "grpc response",
            "hidden reasoning",
            "internal token",
            "actorid",
            "sellerid",
            "runid",
            "conversationid",
            "settlementid",
            "settlementdetailid",
            "orderproductid",
            "requestedperiod",
            "includedstartdate",
            "datathrough",
            "grosssaleamount",
            "payoutamount",
            "statuscounts",
            "weeklysettlements",
            "changeratepercent");

    public ValidatedAnswer validateAndChunk(String candidate) {
        if (!isSafe(candidate)) {
            throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        String answer = candidate.strip();
        return new ValidatedAnswer(answer, chunk(answer));
    }

    private boolean isSafe(String candidate) {
        if (candidate == null || candidate.isBlank()
                || candidate.indexOf('?') >= 0 || candidate.indexOf('？') >= 0
                || candidate.contains("```")
                || candidate.indexOf('{') >= 0 || candidate.indexOf('}') >= 0
                || UUID_PATTERN.matcher(candidate).find()
                || INTERNAL_IDENTIFIER_PATTERN.matcher(candidate).find()
                || KOREAN_QUESTION_ENDING.matcher(candidate.strip()).find()) {
            return false;
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        return FORBIDDEN_INTERNAL_TERMS.stream().noneMatch(normalized::contains);
    }

    private List<String> chunk(String answer) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < answer.length()) {
            int remainingCodePoints = answer.codePointCount(start, answer.length());
            int chunkCodePoints = Math.min(MAX_CHUNK_CODE_POINTS, remainingCodePoints);
            int end = answer.offsetByCodePoints(start, chunkCodePoints);
            chunks.add(answer.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }

    public record ValidatedAnswer(String answer, List<String> chunks) {

        public ValidatedAnswer {
            chunks = List.copyOf(chunks);
        }
    }
}
