package com.prompthub.user.admin.presentation.controller;

import com.prompthub.exception.BusinessException;
import com.prompthub.presentation.dto.ApiResult;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterListQuery;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterPageResult;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterReviewResult;
import com.prompthub.user.admin.application.dto.ApproveSellerCommand;
import com.prompthub.user.admin.application.dto.RejectSellerCommand;
import com.prompthub.user.admin.application.usecase.AdminSellerUseCase;
import com.prompthub.user.admin.presentation.dto.request.RejectSellerRegisterRequest;
import com.prompthub.user.admin.presentation.dto.response.AdminSellerRegisterResponse;
import com.prompthub.user.admin.presentation.dto.response.AdminSellerRegisterReviewResponse;
import com.prompthub.user.global.exception.UserErrorCode;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "관리자 - 판매자", description = "관리자 판매자 등록 신청 심사")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminSellerController {

    private final AdminSellerUseCase adminSellerUseCase;

    @Operation(summary = "판매자 신청 목록 조회", description = "상태 필터 및 페이지네이션 지원. 역할: ADMIN")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/sellers/register")
    public PageResponse<AdminSellerRegisterResponse> listSellerRegisters(
            @Parameter(description = "신청 상태 필터 (PENDING | APPROVED | REJECTED | ALL)", example = "ALL")
            @RequestParam(defaultValue = "ALL") String status,
            @Parameter(description = "페이지 번호 (1부터 시작)", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "페이지당 항목 수", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        AdminSellerRegisterListQuery query = new AdminSellerRegisterListQuery(
                parseStatus(status), page, size);

        AdminSellerRegisterPageResult result = adminSellerUseCase.listSellerRegisters(query);

        List<AdminSellerRegisterResponse> responseData = result.items().stream()
                .map(AdminSellerRegisterResponse::from)
                .toList();

        return PageResponse.success(responseData, result.page(), result.size(), result.total(), result.hasNext());
    }

    @Operation(summary = "판매자 신청 승인", description = "승인 시 SELLER 역할 부여. 역할: ADMIN")
    @ApiResponse(responseCode = "200", description = "승인 성공")
    @ApiResponse(responseCode = "404", description = "신청 내역을 찾을 수 없음 (A008)")
    @PatchMapping("/sellers/register/{registerId}/approve")
    public ApiResult<AdminSellerRegisterReviewResponse> approveSeller(
            @Parameter(description = "판매자 등록 신청 ID") @PathVariable UUID registerId
    ) {
        AdminSellerRegisterReviewResult result = adminSellerUseCase.approve(new ApproveSellerCommand(registerId));
        return ApiResult.success(AdminSellerRegisterReviewResponse.from(result));
    }

    @Operation(summary = "판매자 신청 반려", description = "반려 사유를 포함하여 반려 처리. 역할: ADMIN")
    @ApiResponse(responseCode = "200", description = "반려 성공")
    @ApiResponse(responseCode = "404", description = "신청 내역을 찾을 수 없음 (A008)")
    @PatchMapping("/sellers/register/{registerId}/reject")
    public ApiResult<AdminSellerRegisterReviewResponse> rejectSeller(
            @Parameter(description = "판매자 등록 신청 ID") @PathVariable UUID registerId,
            @Valid @RequestBody RejectSellerRegisterRequest request
    ) {
        AdminSellerRegisterReviewResult result = adminSellerUseCase.reject(
                new RejectSellerCommand(registerId, request.rejectReason()));
        return ApiResult.success(AdminSellerRegisterReviewResponse.from(result));
    }

    private static SellerRegisterStatus parseStatus(String statusParam) {
        return switch (statusParam.toUpperCase()) {
            case "PENDING" -> SellerRegisterStatus.PENDING;
            case "APPROVED" -> SellerRegisterStatus.APPROVED;
            case "REJECTED" -> SellerRegisterStatus.REJECTED;
            case "ALL" -> null;
            default -> throw new BusinessException(UserErrorCode.VALIDATION_FAILED);
        };
    }
}
