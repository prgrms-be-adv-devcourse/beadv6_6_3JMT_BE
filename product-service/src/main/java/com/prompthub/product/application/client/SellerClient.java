package com.prompthub.product.application.client;

import java.util.UUID;

public interface SellerClient {

	SellerInfo getSellerInfo(UUID userId);
}
