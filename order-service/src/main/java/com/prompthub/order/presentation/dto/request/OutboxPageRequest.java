package com.prompthub.order.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "실패한 Outbox 이벤트 페이지 조회 조건")
public record OutboxPageRequest(
    @Min(1)
    @Schema(description = "페이지 번호. 1부터 시작하며 생략 시 1", example = "1", defaultValue = "1", minimum = "1")
    Integer page,

    @Min(1)
    @Max(100)
    @Schema(description = "페이지 크기. 1 이상 100 이하이며 생략 시 20", example = "20", defaultValue = "20", minimum = "1", maximum = "100")
    Integer size
) {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;

    public OutboxPageRequest resolve() {
        return new OutboxPageRequest(
            page == null ? DEFAULT_PAGE : page,
            size == null ? DEFAULT_SIZE : size
        );
    }
}
