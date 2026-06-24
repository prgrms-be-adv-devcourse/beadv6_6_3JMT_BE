package com.prompthub.order.infra.seller;

import java.util.List;
import java.util.UUID;

public record SellerNicknameRequest(
	List<UUID> sellerIds
) {
}
