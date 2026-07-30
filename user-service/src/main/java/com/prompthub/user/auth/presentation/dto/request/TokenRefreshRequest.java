package com.prompthub.user.auth.presentation.dto.request;

import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "토큰 재발급 요청")
public record TokenRefreshRequest(
        @Schema(description = "JWT Refresh Token", example = "eyJraWQiOiJ1c2VyLXNlcnZpY2UtcnNhIiwiYWxnIjoiUlMyNTYifQ.eyJzdWIiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEiLCJleHAiOjE3ODU5ODQ5OTEsInR5cGUiOiJyZWZyZXNoIiwiaWF0IjoxNzg1MzgwMTkxfQ.leRYYPkBYd9qbgqDBaR5a325NzXoR3Nde1Ehk1MfceqdAV3_QolrqkWhOZwZhJmodS1De0xIqjtS9ikxmoGtDKd3B9JxMXAedQ5D4AkXtQToizOpR_tz9XxXDH4ZvXiwjn2WbBfotJk_uTfeFzZHy93BjIlfiHHBu1a3csVdF7Az-yKweVxnOGxytYkB6R-r7j40BHAeXn2FBvcO9lhpM4gcGhgNRXRSXR2T0iX-lFLiBRK0UWh9wATu5bGQ0zxzEeTa9FhZrHiOpVqW4awwByXKkLq6MVrvTQB6638CjlzOrgYJyy-hs8VoPL9DApZL5JA63bOB8lehY3MoyRdWwQ")
        @NotBlank String refreshToken
) {

    public TokenRefreshCommand toCommand() {
        return new TokenRefreshCommand(refreshToken);
    }
}
