package com.prompthub.user.seller.application.usecase;

import com.prompthub.user.seller.application.dto.RegisterSellerCommand;
import com.prompthub.user.seller.application.dto.RegisterSellerResult;

public interface SellerUseCase {
    RegisterSellerResult register(RegisterSellerCommand command);
}
