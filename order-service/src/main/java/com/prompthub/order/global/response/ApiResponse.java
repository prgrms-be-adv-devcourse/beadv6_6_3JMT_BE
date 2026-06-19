package com.prompthub.order.global.response;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiResponse<T> {

    private static final String SUCCESS_MESSAGE = "success";

    private final boolean success;
    private final T data;
    private final String message;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, SUCCESS_MESSAGE);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, SUCCESS_MESSAGE);
    }
}
