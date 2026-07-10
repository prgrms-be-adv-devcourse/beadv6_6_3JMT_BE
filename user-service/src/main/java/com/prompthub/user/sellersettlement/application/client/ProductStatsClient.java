package com.prompthub.user.sellersettlement.application.client;

import com.prompthub.user.sellersettlement.application.dto.SellerProductStats;
import java.util.UUID;

public interface ProductStatsClient {

    SellerProductStats getSellerProductStats(UUID sellerId);
}
