package com.prompthub.user.admin.application.usecase;

import com.prompthub.user.admin.application.dto.AdminSellerRegisterListQuery;
import com.prompthub.user.admin.application.dto.AdminSellerRegisterPageResult;

public interface AdminSellerUseCase {
    AdminSellerRegisterPageResult listSellerRegisters(AdminSellerRegisterListQuery query);
}
