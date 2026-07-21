package com.prompthub.admin.seller.application.dto;

import java.util.UUID;

public record RejectSellerCommand(UUID registerId, String rejectReason) {
}
