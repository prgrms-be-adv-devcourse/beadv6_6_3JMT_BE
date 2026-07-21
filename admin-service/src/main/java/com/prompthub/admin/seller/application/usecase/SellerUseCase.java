package com.prompthub.admin.seller.application.usecase;

import com.prompthub.admin.seller.application.dto.ApproveSellerCommand;
import com.prompthub.admin.seller.application.dto.RejectSellerCommand;
import com.prompthub.admin.seller.application.dto.SellerRegisterListQuery;
import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;

public interface SellerUseCase {
	SellerRegisterPageResult listSellerRegisters(SellerRegisterListQuery query);
	SellerRegisterReviewResult approve(ApproveSellerCommand command);
	SellerRegisterReviewResult reject(RejectSellerCommand command);
}
