package com.prompthub.presentation.dto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiResult<T> {

    private static final String SUCCESS_MESSAGE = "success";

    private final boolean success;
    private final T data;
    private final String message;

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(true, data, SUCCESS_MESSAGE);
    }

    public static ApiResult<Void> success() {
        return new ApiResult<>(true, null, SUCCESS_MESSAGE);
    }
}
