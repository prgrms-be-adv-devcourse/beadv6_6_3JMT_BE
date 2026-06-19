package com.prompthub.presentation.dto;

import java.util.List;

public record PageResponse<T>(
        boolean success,
        List<T> data,
        String message,
        Meta meta
) {

    private static final String SUCCESS_MESSAGE = "success";

    public static <T> PageResponse<T> success(
            List<T> data,
            int page,
            int size,
            long total,
            boolean hasNext
    ) {
        return new PageResponse<>(true, data, SUCCESS_MESSAGE, new Meta(page, size, total, hasNext));
    }

    public record Meta(
            int page,
            int size,
            long total,
            boolean hasNext
    ) {
    }
}
