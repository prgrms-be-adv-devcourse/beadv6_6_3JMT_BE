package com.prompthub.admin.order.application.port;

import com.prompthub.admin.order.application.dto.OrderUserProfile;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface OrderUserProfileQueryPort {

    Map<UUID, OrderUserProfile> findProfilesByUserIds(List<UUID> userIds);
}
