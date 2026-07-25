package com.prompthub.admin.order.application.dto;

import java.util.UUID;

public record OrderUserProfile(UUID userId, String name, String profileImageUrl) {
}
