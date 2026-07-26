package com.prompthub.ai.settlement.presentation.rest.dto;

public record AiApiResponse<T>(
        boolean success,
        T data,
        String message
) {

    public static <T> AiApiResponse<T> success(T data) {
        return new AiApiResponse<>(true, data, "success");
    }

    public static <T> AiApiResponse<T> accepted(T data) {
        return new AiApiResponse<>(true, data, "accepted");
    }
}
