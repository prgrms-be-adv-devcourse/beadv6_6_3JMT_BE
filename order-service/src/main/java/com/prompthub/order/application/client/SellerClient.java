package com.prompthub.order.application.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SellerClient {

	Map<UUID, String> getSellerNicknames(List<UUID> sellerIds);
}
