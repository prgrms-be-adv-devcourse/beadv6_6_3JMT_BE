package com.prompthub.user.admin.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "판매자 신청 반려 요청")
public record RejectSellerRegisterRequest(
        @Schema(description = "반려 사유", example = "포트폴리오가 확인되지 않습니다. 샘플을 보완 후 재신청해 주세요.")
        @NotBlank String rejectReason
) {
}
