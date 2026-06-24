package com.prompthub.user.admin.presentation.controller;

import com.prompthub.exception.BusinessException;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterListQuery;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterPageResult;
import com.prompthub.user.admin.application.usecase.AdminSellerUseCase;
import com.prompthub.user.admin.presentation.dto.response.AdminSellerRegisterResponse;
import com.prompthub.user.global.exception.UserErrorCode;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
