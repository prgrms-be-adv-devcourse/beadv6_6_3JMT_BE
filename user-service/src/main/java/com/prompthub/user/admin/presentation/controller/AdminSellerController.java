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

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminSellerController {

    private final AdminSellerUseCase adminSellerUseCase;

    @GetMapping("/sellers/register")
    public PageResponse<AdminSellerRegisterResponse> listSellerRegisters(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "1") int page,
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

    @PatchMapping("/sellers/register/{registerId}/approve")
    public ApiResult<AdminSellerRegisterReviewResponse> approveSeller(
            @PathVariable UUID registerId
    ) {
        AdminSellerRegisterReviewResult result = adminSellerUseCase.approve(new ApproveSellerCommand(registerId));
        return ApiResult.success(AdminSellerRegisterReviewResponse.from(result));
    }

    @PatchMapping("/sellers/register/{registerId}/reject")
    public ApiResult<AdminSellerRegisterReviewResponse> rejectSeller(
            @PathVariable UUID registerId,
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
