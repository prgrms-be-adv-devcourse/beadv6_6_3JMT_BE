package com.prompthub.admin.user.dto;

import java.util.UUID;

public record RejectSellerCommand(UUID registerId, String rejectReason) {
}
