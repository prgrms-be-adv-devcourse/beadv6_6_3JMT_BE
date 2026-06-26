package com.prompthub.user.user.presentation.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.user.user.application.usecase.UserUseCase;
import com.prompthub.user.user.presentation.dto.request.UpdateProfileRequest;
import com.prompthub.user.user.presentation.dto.response.UpdateProfileResponse;
import com.prompthub.user.user.presentation.dto.response.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

@Tag(name = "회원", description = "회원 프로필 조회 및 수정")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserUseCase userUseCase;

    @Operation(summary = "내 프로필 조회", description = "프로필 정보와 판매자 신청 상태를 함께 반환. 역할: BUYER / SELLER")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음 (A001)")
    @GetMapping("/me")
    public ApiResult<UserProfileResponse> getMe(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId
    ) {
        return ApiResult.success(UserProfileResponse.from(userUseCase.getMyProfile(userId)));
    }

    @Operation(summary = "프로필 수정", description = "수정할 필드만 포함 (Partial Update). password는 local 가입 사용자만 허용. 역할: BUYER / SELLER")
    @ApiResponse(responseCode = "200", description = "수정 성공 — 실제로 변경된 필드만 응답에 포함")
    @ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일 (A007)")
    @PatchMapping("/me")
    public ApiResult<UpdateProfileResponse> updateMe(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") UUID userId,
            @RequestBody UpdateProfileRequest request
    ) {
        return ApiResult.success(UpdateProfileResponse.from(userUseCase.updateProfile(request.toCommand(userId))));
    }
}
