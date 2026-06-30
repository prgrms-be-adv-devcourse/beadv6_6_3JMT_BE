package com.prompthub.user.admin.application.usecase;

import com.prompthub.user.admin.application.dto.AdminSellerRegisterListQuery;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterPageResult;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterReviewResult;
import com.prompthub.user.admin.application.dto.ApproveSellerCommand;
import com.prompthub.user.admin.application.dto.RejectSellerCommand;

public interface AdminSellerUseCase {
    AdminSellerRegisterPageResult listSellerRegisters(AdminSellerRegisterListQuery query);
    AdminSellerRegisterReviewResult approve(ApproveSellerCommand command);
    AdminSellerRegisterReviewResult reject(RejectSellerCommand command);
}
