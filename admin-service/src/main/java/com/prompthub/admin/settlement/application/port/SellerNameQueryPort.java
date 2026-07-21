package com.prompthub.admin.settlement.application.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SellerNameQueryPort {

    Map<UUID, String> findNamesBySellerIds(List<UUID> sellerIds);
}
