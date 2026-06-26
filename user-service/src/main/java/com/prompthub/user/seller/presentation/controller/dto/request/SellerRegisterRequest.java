package com.prompthub.user.seller.presentation.controller.dto.request;

import java.util.List;
import java.util.UUID;

import com.prompthub.user.seller.application.dto.RegisterSellerCommand;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "판매자 등록 신청 요청")
public record SellerRegisterRequest(
        @Schema(description = "주력 카테고리 (최대 3개)", example = "[\"marketing\", \"coding\"]")
        @NotNull @Size(min = 1, max = 3) List<String> categories,
        @Schema(description = "판매할 프롬프트 소개", example = "마케팅 카피·블로그 글쓰기용 GPT 프롬프트를 주로 만듭니다.", nullable = true)
        String introduction,
        @Schema(description = "블로그/포트폴리오/SNS 링크", example = "https://blog.example.com", nullable = true)
        String portfolioUrl,
        @Schema(description = "판매자 이용약관 및 정산 정책 동의 여부 (true만 허용)", example = "true")
        @NotNull @AssertTrue Boolean agreedToTerms
) {
    public RegisterSellerCommand toCommand(UUID userId) {
        return new RegisterSellerCommand(
                userId,
                categories,
                introduction,
                portfolioUrl,
                agreedToTerms
        );
    }
}
