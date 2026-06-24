package com.prompthub.user.admin.application.dto;

import java.util.UUID;

public record RejectSellerCommand(UUID registerId, String rejectReason) {
}
